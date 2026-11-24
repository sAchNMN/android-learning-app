package com.szc.learning.music.data.room

import android.content.Context
import android.os.SystemClock
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.data.OnlineSourceRouter
import com.szc.learning.music.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/** 房间界面读的全部状态。 */
data class TogetherUiState(
    val active: Boolean = false,
    val roomCode: String = "",
    val isHost: Boolean = false,
    /** 数据面**实际**在用的通道，不是"能不能用"。 */
    val mode: SyncMode? = null,
    val modeReason: String = "",
    val members: List<MemberInfo> = emptyList(),
    /** 单程延迟估计，-1 表示还没同步上。 */
    val latencyMs: Long = -1L,
    val synced: Boolean = false,
    val busy: Boolean = false,
)

/**
 * 一起听的编排器。
 *
 * ## 双通道结构
 *
 * ```
 *   控制面（恒开）  后端 WebSocket  ← 房间成员、模式通知、时钟握手
 *   数据面（择优）  局域网 UDP      ← 播放状态快照
 *                  （不通时回落控制面）
 * ```
 *
 * 为什么控制面不跟着切：局域网判定需要双方都在线来回探测，
 * 如果判定期间控制面也走 UDP，那"探测失败"就等于"信令也断了"，
 * 房间会静默死掉，而界面上还显示着成员列表。
 *
 * ## 同步模型
 *
 * 房主是**唯一时间权威**：它把「当前播到哪、此刻是房主时钟的第几毫秒」
 * 打包成快照广播出去；成员收到后按自己的时钟推算"现在该播到哪"。
 *
 * 这套模型能容忍丢包：丢一条，下一条心跳就把它纠回来。
 * 反过来做"播放/暂停/seek 三条命令流"就不行了 —— 丢一条 "play"，
 * 那台设备就永远卡在暂停，而且没人知道。
 */
class TogetherListenManager(
    private val appContext: Context,
    private val player: PlayerController,
    private val router: OnlineSourceRouter,
    private val deviceId: String,
    private val baseUrl: String,
    private val parentScope: CoroutineScope,
) {

    private val api = RoomApi(baseUrl)
    private val clock = ClockSync()

    private val _state = MutableStateFlow(TogetherUiState())
    val state: StateFlow<TogetherUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private var session: CoroutineScope? = null
    private var wan: WanTransport? = null
    private var lan: LanTransport? = null

    private var room: RoomSnapshot = RoomSnapshot()
    private var isHost = false
    private var nickname = ""

    private val seq = AtomicLong(0L)

    /** 房主侧：本轮探测已回 ACK 的成员。 */
    private val acked: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** 房主侧：上次广播过的曲目 key，用来识别"切歌"这个离散事件。 */
    @Volatile
    private var lastBroadcastKey: String? = null

    @Volatile
    private var lastBroadcastPlaying: Boolean? = null

    /** 成员侧：最近一次收到的房主状态。 */
    @Volatile
    private var remote: RoomMessage? = null

    /** 成员侧：已应用的序号，丢弃乱序到达的旧状态。 */
    @Volatile
    private var appliedSeq = 0L

    /** 成员侧：当前正在播的曲目 key，用来判断"是不是切歌"。 */
    @Volatile
    private var currentKey: String? = null

    @Volatile
    private var lastCorrectionAt = 0L

    private var pendingStart: Job? = null

    /**
     * 探测是"发三轮、每轮等 700ms"的长操作，成员轮询和定期复核都想调它。
     * 不加锁的话两条协程会同时改 [acked]，还可能一个刚判成功另一个判失败。
     */
    private val lanGuard = Mutex()

    val active: Boolean get() = _state.value.active

    // ---------------------------------------------------------------- 进出房间

    suspend fun create(nickname: String): Boolean = enter(nickname, host = true, code = null)

    suspend fun join(code: String, nickname: String): Boolean =
        enter(nickname, host = false, code = code.trim())

    private suspend fun enter(nickname: String, host: Boolean, code: String?): Boolean {
        if (_state.value.active) return false
        _state.value = _state.value.copy(busy = true)

        val me = presence(nickname, host)
        val snapshot = if (host) {
            api.create(me)
        } else {
            if (code.isNullOrBlank()) {
                _state.value = _state.value.copy(busy = false)
                _events.tryEmit("房间码不能为空")
                return false
            }
            api.join(code, me)
        }

        if (snapshot == null || snapshot.code.isBlank()) {
            _state.value = _state.value.copy(busy = false)
            _events.tryEmit("连不上房间服务，一起听需要后端在线")
            return false
        }

        // 房间启动涉及 socket 绑定、WebSocket 建连，任何一步失败都不该把
        // busy 卡在 true —— 那会让"创建房间"按钮永久变灰，只能杀进程
        val started = runCatching { start(snapshot, nickname, host) }
        if (started.isFailure) {
            lan?.close()
            wan?.close()
            lan = null
            wan = null
            session?.cancel()
            session = null
            _state.value = TogetherUiState()
            _events.tryEmit("房间启动失败：${started.exceptionOrNull()?.message ?: "未知原因"}")
            return false
        }
        return true
    }

    /**
     * 采集自己的网络指纹。
     *
     * [MemberInfo.lanIp] 只是**自报**，房主真正用的是收包的源地址 ——
     * 多网卡设备（比如同时开着 WiFi 和 VPN）自报的那个很可能不是能通的那个。
     */
    private fun presence(nickname: String, host: Boolean): MemberInfo {
        val net = NetworkSnapshot.capture(appContext)
        return MemberInfo(
            deviceId = deviceId,
            nickname = nickname,
            host = host,
            subnet = net.subnetKey,
            gateway = net.gateway,
            lanIp = net.ip,
            lanPort = LanTransport.PORT,
        )
    }

    private fun start(snapshot: RoomSnapshot, nickname: String, host: Boolean) {
        val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob())
        session = scope
        room = snapshot
        isHost = host
        this.nickname = nickname
        clock.reset()
        acked.clear()

        // 控制面：一定开。哪怕局域网可用，房间成员和模式通知也得走它
        wan = WanTransport(deviceId, nickname, snapshot.code, scope, baseUrl)

        // 数据面候选：socket 先开着（成本几乎为零），探测通过才会真的用它发状态。
        // 成员侧也必须开 —— 房主的探测包打过来，得有人在听
        val net = NetworkSnapshot.capture(appContext)
        lan = LanTransport(deviceId, scope, net.broadcast, LanTransport.PORT)

        _state.value = TogetherUiState(
            active = true,
            roomCode = snapshot.code,
            isHost = host,
            mode = SyncMode.WAN,
            modeReason = if (net.usableForLan) "正在探测局域网" else "当前不是 WiFi，走公网",
            members = snapshot.members,
        )

        watchIncoming(scope)

        if (host) startHost(scope) else startGuest(scope)
    }

    fun leave() {
        val scope = session
        val code = room.code
        val wasHost = isHost
        session = null
        pendingStart?.cancel()
        pendingStart = null
        lan?.close()
        wan?.close()
        lan = null
        wan = null
        player.restoreSpeed()
        if (scope != null) {
            parentScope.launch(Dispatchers.IO) { api.leave(code, deviceId) }
        }
        scope?.cancel()
        clock.reset()
        remote = null
        currentKey = null
        lastBroadcastKey = null
        lastBroadcastPlaying = null
        appliedSeq = 0L
        room = RoomSnapshot()
        isHost = false
        _state.value = TogetherUiState()
        if (wasHost) _events.tryEmit("已解散房间")
    }

    // ---------------------------------------------------------------- 收包分发

    private fun watchIncoming(scope: CoroutineScope) {
        listOfNotNull(wan, lan).forEach { transport ->
            scope.launch {
                transport.incoming.collect { incoming -> handle(incoming) }
            }
        }
    }

    private suspend fun handle(incoming: Incoming) {
        val message = incoming.message
        // 自己发的包：UDP 广播会绕回来，公网服务端也可能回显
        if (message.from == deviceId) return

        when (message.type) {
            RoomMessage.PROBE -> {
                // 房主的探测包顺带告诉了我们"谁是房主"：服务端没下发 hostId 时靠这个兜底
                if (message.host && message.from.isNotBlank() && room.hostId != message.from) {
                    room = room.copy(hostId = message.from)
                }
                // 有人来找：立刻回执。房主据此确认"这个 IP 真的能通"
                sendToDevice(message.from, RoomMessage(type = RoomMessage.PROBE_ACK, from = deviceId))
            }

            RoomMessage.PROBE_ACK -> {
                if (isHost) acked.add(message.from)
            }

            RoomMessage.TIME_REQ -> {
                val now = SystemClock.elapsedRealtime()
                sendToDevice(
                    message.from,
                    RoomMessage(
                        type = RoomMessage.TIME_ACK,
                        from = deviceId,
                        t1 = message.t1,
                        t2 = now,
                        // t3 尽量贴近真正发出的时刻，中间不放耗时操作
                        t3 = SystemClock.elapsedRealtime(),
                    ),
                )
            }

            RoomMessage.TIME_ACK -> {
                if (!isHost) {
                    clock.onAck(message.t1, message.t2, message.t3, SystemClock.elapsedRealtime())
                    publishClock()
                }
            }

            RoomMessage.STATE -> if (!isHost) onRemoteState(message)

            RoomMessage.MEMBERS -> message.members?.let { members ->
                _state.value = _state.value.copy(members = members)
            }

            RoomMessage.MODE -> if (!isHost) applyRemoteMode(message)

            RoomMessage.HELLO -> if (isHost) refreshMembers()
        }
    }

    private fun publishClock() {
        _state.value = _state.value.copy(
            latencyMs = clock.oneWayMs,
            synced = clock.synced,
        )
    }

    // ---------------------------------------------------------------- 房主

    private fun startHost(scope: CoroutineScope) {
        // 成员变化靠轮询：房间是低频场景（人不会每秒进出），
        // 为它单独开一条长连接不值得
        scope.launch {
            while (isActive) {
                refreshMembers()
                lanGuard.withLock { evaluateLan() }
                delay(MEMBER_POLL_MS)
            }
        }

        // 状态变化立刻广播：切歌和暂停必须即时，等心跳会有两秒延迟
        scope.launch {
            player.state.collect { state ->
                if (!isHost) return@collect
                val track = state.track ?: return@collect
                // 第一次广播是"同步房间现状"，不是"切歌"。这时给提前量会让成员
                // 从 0 开始放一首房主已经播到一半的歌，听起来就是直接跳回开头
                val first = lastBroadcastKey == null
                val turnedPage = track.key != lastBroadcastKey
                val toggled = state.isPlaying != lastBroadcastPlaying
                if (!turnedPage && !toggled) return@collect
                lastBroadcastKey = track.key
                lastBroadcastPlaying = state.isPlaying
                broadcastState(track, state.isPlaying, lead = turnedPage && !first)
            }
        }

        // 心跳：兜住丢包，也给中途进来的成员一次对齐机会
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (!isHost) continue
                val track = player.state.value.track ?: continue
                broadcastState(track, player.isPlayingNow(), lead = false)
            }
        }

        // 局域网探测：定期复核，成员进出、切网都要重判
        scope.launch {
            while (isActive) {
                delay(LAN_RECHECK_MS)
                lanGuard.withLock { evaluateLan() }
            }
        }
    }

    private suspend fun refreshMembers() {
        val snapshot = api.snapshot(room.code) ?: return
        room = snapshot
        _state.value = _state.value.copy(members = snapshot.members)
        wan?.broadcast(
            RoomMessage(type = RoomMessage.MEMBERS, from = deviceId, members = snapshot.members),
        )
    }

    /**
     * 判定局域网是否可用。
     *
     * 两道关，缺一不可：
     * 1. **网段与网关一致** —— 排除掉"同一个房间但一个在家一个在公司"
     * 2. **探测收到全员 ACK** —— 排除掉 AP 隔离（同一台路由器但客户端之间禁止互访，
     *    酒店和公共 WiFi 的默认配置，光看网段完全发现不了）
     */
    private suspend fun evaluateLan() {
        val others = room.members.filter { it.deviceId != deviceId }
        if (others.isEmpty()) {
            // 一个人待着的时候别说"已直连"——那是假的，其实是没人在听
            if (_state.value.modeReason != REASON_ALONE) {
                _state.value = _state.value.copy(mode = SyncMode.WAN, modeReason = REASON_ALONE)
            }
            return
        }
        val net = NetworkSnapshot.capture(appContext)
        val sameNet = net.usableForLan && others.all {
            it.subnet != null && it.subnet == net.subnetKey && it.gateway == net.gateway
        }
        if (!sameNet) {
            setMode(SyncMode.WAN, if (net.usableForLan) "成员不在同一网络" else "当前网络不支持直连")
            return
        }

        val transport = lan ?: run {
            setMode(SyncMode.WAN, "局域网端口未就绪")
            return
        }
        if (!transport.opened) {
            setMode(SyncMode.WAN, "局域网端口被占用")
            return
        }

        acked.clear()
        // 多打几轮：刚进房的成员可能 socket 还没起来，第一轮必然收不到
        for (round in 0 until PROBE_ROUNDS) {
            transport.shout(
                RoomMessage(
                    type = RoomMessage.PROBE,
                    from = deviceId,
                    host = true,
                    probeRound = round.toLong(),
                ),
            )
            delay(PROBE_WAIT_MS)
            if (others.all { acked.contains(it.deviceId) }) break
        }

        val responded = others.count { acked.contains(it.deviceId) }
        if (responded == others.size) {
            setMode(SyncMode.LAN, "同网段，${responded}/${others.size} 台直连成功")
            broadcastMembers()
        } else {
            // 典型的 AP 隔离：网段一样，包发得出去，但对方收不到
            setMode(
                SyncMode.WAN,
                "局域网不可达（${responded}/${others.size}），可能存在客户端隔离",
            )
        }
    }

    private fun broadcastMembers() {
        val members = room.members
        session?.launch {
            wan?.broadcast(RoomMessage(type = RoomMessage.MEMBERS, from = deviceId, members = members))
        }
    }

    private fun setMode(mode: SyncMode, reason: String) {
        if (_state.value.mode == mode && _state.value.modeReason == reason) return
        _state.value = _state.value.copy(mode = mode, modeReason = reason)
        session?.launch {
            wan?.broadcast(
                RoomMessage(
                    type = RoomMessage.MODE,
                    from = deviceId,
                    mode = mode.name,
                    reason = reason,
                ),
            )
        }
    }

    private fun broadcastState(track: MusicTrack, playing: Boolean, lead: Boolean) {
        val scope = session ?: return
        val now = SystemClock.elapsedRealtime()
        val message = RoomMessage(
            type = RoomMessage.STATE,
            from = deviceId,
            seq = seq.incrementAndGet(),
            songKey = track.key,
            track = TrackPayload.of(track),
            // 切歌那一刻 MediaController 还没切过去，positionNow() 读到的仍是上一首的位置。
            // 锚点必须写 0，否则成员会从上一首播到的第 180 秒处开始放新歌
            anchorPosMs = if (lead) 0L else player.positionNow(),
            anchorHostMs = now,
            playing = playing,
            // 切歌时约定一个未来时刻：各端先各自 prepare，到点一起响。
            // 不给这个提前量的话，谁缓冲快谁先响，一换歌就错开半秒
            startAtHostMs = if (lead) now + LEAD_MS else 0L,
        )
        scope.launch { sendData(message) }
    }

    // ---------------------------------------------------------------- 成员

    private fun startGuest(scope: CoroutineScope) {
        // 时钟握手：3 秒一次足够 —— 设备晶振在 3 秒里漂不了几毫秒
        scope.launch {
            while (isActive) {
                sendToDevice(
                    room.hostId,
                    RoomMessage(type = RoomMessage.TIME_REQ, from = deviceId, t1 = SystemClock.elapsedRealtime()),
                )
                delay(TIME_SYNC_MS)
            }
        }

        // 漂移纠正
        scope.launch {
            while (isActive) {
                delay(CORRECTION_MS)
                correctDrift()
            }
        }
    }

    private fun applyRemoteMode(message: RoomMessage) {
        val mode = runCatching { SyncMode.valueOf(message.mode.orEmpty()) }.getOrNull() ?: return
        val reason = message.reason.orEmpty()
        if (_state.value.mode == mode && _state.value.modeReason == reason) return
        _state.value = _state.value.copy(mode = mode, modeReason = reason)
        if (mode == SyncMode.LAN) {
            // 房主说局域网通了，那自己也得有个 socket 在听 —— 这里通常是已经开着的
            _events.tryEmit("已切到局域网直连")
        }
    }

    private fun onRemoteState(message: RoomMessage) {
        val track = message.track?.toTrack() ?: return
        // 乱序到达的旧快照会把已经追上的进度又拽回去
        if (message.seq in 1..appliedSeq) return
        appliedSeq = message.seq
        remote = message

        if (track.key != currentKey) {
            currentKey = track.key
            // 房主切歌时会先广播一帧 playing=false（setMediaItems 已经发出，
            // 但 isPlaying 还是 false）。这一帧只准备、不起播：
            // 抢跑的结果是本机比房主早响半秒，然后被纠偏硬拽回来，听感是一次跳音
            if (message.playing) {
                scheduleStart(track, message)
            } else {
                schedulePrepare(track, message)
            }
            return
        }

        // 同一首歌。从"准备好但没播"过渡到"该播了"：先跳到房主当前的位置再起播，
        // 否则会从 prepare 那一刻的旧位置开始，白白落后几百毫秒
        if (message.playing && !player.isPlayingNow()) {
            expectedPosition(message)?.let {
                player.seekTo(it)
                lastCorrectionAt = SystemClock.elapsedRealtime()
            }
            player.startNow()
        } else if (!message.playing && player.isPlayingNow()) {
            player.pauseQuiet()
            player.restoreSpeed()
        }
    }

    /** 按房主时钟推算"此刻该播到第几毫秒"。 */
    private fun expectedPosition(message: RoomMessage): Long? {
        if (!clock.synced) return message.anchorPosMs.coerceAtLeast(0L)
        return DriftPolicy.targetPosition(
            anchorPosMs = message.anchorPosMs,
            anchorHostMs = message.anchorHostMs,
            hostNowMs = clock.hostNow(),
        )
    }

    /** 只准备不播：房主还停在"切过去但没开始"的那一帧。 */
    private fun schedulePrepare(track: MusicTrack, message: RoomMessage) {
        val scope = session ?: return
        pendingStart?.cancel()
        pendingStart = scope.launch {
            val playable = ensurePlayable(track) ?: return@launch
            player.prepareSingle(playable, message.anchorPosMs.coerceAtLeast(0L))
        }
    }

    /**
     * 切歌：准备 → 等到约定时刻 → 起播。
     *
     * [ensurePlayable] 可能要联网解析地址，这段时间不计入等待，
     * 所以真正起播可能比约定时刻晚 —— 晚的那点交给漂移纠正去追，
     * 比"为了准点而先播个坏的"要好。
     */
    private fun scheduleStart(track: MusicTrack, message: RoomMessage) {
        val scope = session ?: return
        pendingStart?.cancel()
        pendingStart = scope.launch {
            val playable = ensurePlayable(track)
            if (playable == null) {
                _events.tryEmit("《${track.title}》在这台设备上取不到播放地址")
                return@launch
            }

            // 约定时刻换到本地时钟；未同步时直接从现在开始播
            val startLocal = if (clock.synced && message.startAtHostMs > 0) {
                clock.toLocal(message.startAtHostMs)
            } else {
                SystemClock.elapsedRealtime()
            }
            val wait = startLocal - SystemClock.elapsedRealtime()
            // 位置也要跟着往前走：约定时刻比锚点晚多少，就多播多少
            val offset = if (startLocal > 0 && message.startAtHostMs > 0) {
                (message.startAtHostMs - message.anchorHostMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val position = (message.anchorPosMs + offset).coerceAtLeast(0L)

            player.prepareSingle(playable, position)
            if (wait > 0) delay(wait)
            player.startNow()
        }
    }

    /**
     * 漂移纠正，分三档。
     *
     * 为什么不是"一发现不同就 seek"：seek 会断一下音，几十毫秒的偏差
     * 每两秒 seek 一次，听感就是一直在打嗝。而 4% 的变速人耳几乎察觉不到，
     * 追 100ms 只需要 2.5 秒。只有偏差大到变速追不上（400ms 以上）才动 seek。
     *
     * 这个阈值是参考"双设备同时外放"的可接受错位定的：
     * 100ms 以内听起来是"同一首歌的两个音箱"，200ms 开始像回声。
     */
    private fun correctDrift() {
        val reference = remote ?: return
        if (!reference.playing) {
            player.restoreSpeed()
            return
        }
        if (!player.isPlayingNow()) return

        // seek 之后 ExoPlayer 要几十毫秒才真正落到新位置，
        // 这期间读到的仍是旧值，不设冷却会连着 seek 好几次
        if (SystemClock.elapsedRealtime() - lastCorrectionAt < SEEK_COOLDOWN_MS) return

        val expected = DriftPolicy.targetPosition(
            anchorPosMs = reference.anchorPosMs,
            anchorHostMs = reference.anchorHostMs,
            hostNowMs = clock.hostNow(),
        )
        val drift = expected - player.positionNow()

        when (DriftPolicy.decide(drift)) {
            DriftPolicy.Action.SEEK -> {
                player.seekTo(expected)
                player.restoreSpeed()
                lastCorrectionAt = SystemClock.elapsedRealtime()
            }

            DriftPolicy.Action.SPEED_UP -> player.applySyncSpeed(DriftPolicy.FAST_SPEED)
            DriftPolicy.Action.SLOW_DOWN -> player.applySyncSpeed(DriftPolicy.SLOW_SPEED)
            DriftPolicy.Action.HOLD -> player.restoreSpeed()
        }
    }

    // ---------------------------------------------------------------- 发送

    /** 状态走数据面：局域网通了就用 UDP，否则回落控制面。 */
    private suspend fun sendData(message: RoomMessage) {
        val transport = lan
        if (_state.value.mode == SyncMode.LAN && transport != null) {
            transport.shout(message)
        } else {
            wan?.broadcast(message)
        }
    }

    private suspend fun sendToDevice(target: String, message: RoomMessage) {
        if (target.isBlank()) return
        val transport = lan
        // 只有确认过对方局域网地址才走 UDP：否则包发进黑洞，时钟永远同步不上
        if (transport != null && transport.lanPeers().containsKey(target)) {
            transport.sendTo(target, message)
            return
        }
        wan?.sendTo(target, message)
    }

    private suspend fun ensurePlayable(track: MusicTrack): MusicTrack? {
        // 对方把直链一起传过来了，直接用 —— 省一次取址往返
        if (track.uri.isNotBlank()) return track
        return runCatching { router.play(track) }.getOrNull()?.track
    }

    private companion object {
        /** 切歌提前量。局域网缓冲快，1.2 秒够；公网解析地址时会被漂移纠正兜住。 */
        const val LEAD_MS = 1_200L
        const val HEARTBEAT_MS = 2_000L
        const val MEMBER_POLL_MS = 5_000L
        const val LAN_RECHECK_MS = 15_000L
        const val TIME_SYNC_MS = 3_000L
        const val CORRECTION_MS = 500L
        const val PROBE_ROUNDS = 3
        const val PROBE_WAIT_MS = 700L

        const val REASON_ALONE = "等待其他人加入"

        const val SEEK_COOLDOWN_MS = 1_200L
    }
}
