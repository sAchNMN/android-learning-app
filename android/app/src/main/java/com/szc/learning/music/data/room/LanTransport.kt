package com.szc.learning.music.data.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 局域网直连通道。
 *
 * 用 UDP 而不是在手机上起 HTTP 服务，有三个具体理由：
 * 1. Android 从 10 开始限制后台起服务端口，一堆机型会拦；UDP 只是普通 socket，不受限
 * 2. 状态包是**幂等快照**，丢一条下一条就补上，不需要 TCP 的重传与拥塞控制，
 *    反而避免了"一条丢包卡住后面全部"的队头阻塞
 * 3. 没有握手，发出去就完事，切歌手感不受连接状态影响
 *
 * **为什么是单播而不是组播**：组播在大量家用路由器上默认关闭（IGMP snooping），
 * 尤其是运营商定制光猫。只有探测那一下用子网广播（必须的，因为还不知道对端在哪），
 * 建立对端表之后全部改单播 —— 单播在家用网络里几乎不会失败。
 */
class LanTransport(
    private val myId: String,
    private val scope: CoroutineScope,
    private val broadcastAddress: String?,
    private val port: Int = PORT,
) : RoomTransport {

    override val mode: SyncMode = SyncMode.LAN

    private val _incoming = MutableSharedFlow<Incoming>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    private val _alive = MutableStateFlow(false)
    override val alive: StateFlow<Boolean> = _alive.asStateFlow()

    /** deviceId → ip。以实际收包的源地址为准，不信任对方自报的 IP。 */
    private val peers = ConcurrentHashMap<String, String>()

    @Volatile
    private var hostIp: String? = null

    private val socket: DatagramSocket? = runCatching {
        DatagramSocket(null).apply {
            // reuseAddress 必须在 bind 之前设，否则同一台机器上前后两次进房间会绑定失败
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(port))
        }
    }.getOrNull()

    private var receiveJob: Job? = null

    /** 端口绑不上（被别的进程占了）时上层要能立刻回落公网通道。 */
    val opened: Boolean get() = socket != null

    init {
        val active = socket
        if (active != null) {
            _alive.value = true
            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(active) }
        }
    }

    private suspend fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(MAX_PACKET)
        while (scope.isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(packet) }.isSuccess
            // 收不到只有一种原因：socket 被 close 了，正常退出
            if (!received) break
            val message = RoomCodec.decode(packet.data, packet.length) ?: continue
            val sourceIp = packet.address?.hostAddress ?: continue
            // 自己发出的广播会绕回来（单机、或者路由器回环），必须丢掉，
            // 否则房主会把自己当成一个成员，成员列表里多出一个自己
            if (message.from == myId) continue
            if (message.from.isNotBlank()) {
                peers[message.from] = sourceIp
                if (message.host) hostIp = sourceIp
            }
            _incoming.tryEmit(Incoming(message, sourceIp))
        }
        _alive.value = false
    }

    override suspend fun broadcast(message: RoomMessage) {
        val bytes = RoomCodec.encode(message)
        val targets = peers.values.toSet()
        if (targets.isEmpty()) {
            sendSubnetBroadcast(bytes)
        } else {
            targets.forEach { sendToIp(it, bytes) }
        }
    }

    override suspend fun shout(message: RoomMessage) {
        val bytes = RoomCodec.encode(message)
        // 广播 + 已知对端各来一份：广播在极少数路由器上会被拦，单播兜底；
        // 反过来单播只能覆盖已知对端，广播负责发现新的。两个一起发才稳。
        sendSubnetBroadcast(bytes)
        peers.values.toSet().forEach { sendToIp(it, bytes) }
    }

    override suspend fun sendTo(deviceId: String, message: RoomMessage) {
        val ip = peers[deviceId] ?: return
        sendToIp(ip, RoomCodec.encode(message))
    }

    override fun lanPeers(): Map<String, String> = HashMap(peers)

    override fun close() {
        receiveJob?.cancel()
        receiveJob = null
        runCatching { socket?.close() }
        _alive.value = false
    }

    private fun sendSubnetBroadcast(bytes: ByteArray) {
        val target = broadcastAddress ?: return
        runCatching { InetAddress.getByName(target) }.getOrNull()?.let { sendToIp(it, bytes) }
        // 子网定向广播被路由丢弃时，255.255.255.255 还有机会
        runCatching { InetAddress.getByName("255.255.255.255") }
            .getOrNull()
            ?.let { sendToIp(it, bytes) }
    }

    private fun sendToIp(ip: String, bytes: ByteArray) {
        val address = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return
        sendToIp(address, bytes)
    }

    private fun sendToIp(address: InetAddress, bytes: ByteArray) {
        runCatching {
            socket?.send(DatagramPacket(bytes, bytes.size, address, port))
        }
    }

    companion object {
        /**
         * 固定端口。
         *
         * 不用随机端口是因为**成员要先能打中房主**，随机端口就得靠后端把端口转发下来，
         * 局域网的优势瞬间消失。同端口在每台设备上互不冲突（各自独立的网络栈）。
         */
        const val PORT = 47820

        /** UDP 理论上限 65507，但状态包最大也就几 KB，开 8K 够了。 */
        private const val MAX_PACKET = 8192
    }
}
