package com.szc.learning.music.data.room

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.szc.learning.core.network.SharedHttp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 公网中转通道。
 *
 * 两台设备不在同一网络时，唯一的选择是让双方都能主动连上的第三方来转发。
 * 选 WebSocket 而不是长轮询：同步要的是"变化即刻到达"，
 * 长轮询每次都得等上一个请求返回，光往返就吃掉几百毫秒，声音对不齐。
 *
 * 心跳 15 秒：运营商 NAT 通常 30 秒清空闲映射，心跳要比它短才有意义。
 * 断线会自己重连，重连成功后上层会重新做一次全量状态对齐。
 */
class WanTransport(
    private val myId: String,
    private val nickname: String,
    private val roomCode: String,
    private val scope: CoroutineScope,
    private val baseUrl: String,
) : RoomTransport {

    override val mode: SyncMode = SyncMode.WAN

    private val _incoming = MutableSharedFlow<Incoming>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()

    private val _alive = MutableStateFlow(false)
    override val alive: StateFlow<Boolean> = _alive.asStateFlow()

    private val client = SharedHttp.base.newBuilder()
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 公网下没有"局域网对端"这回事，恒为空表。 */
    private val noPeers = ConcurrentHashMap<String, String>()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private var retries = 0

    private val url: String? = buildUrl()

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            retries = 0
            _alive.value = true
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = RoomCodec.decode(text) ?: return
            if (message.from == myId) return
            _incoming.tryEmit(Incoming(message))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _alive.value = false
            reconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _alive.value = false
            reconnect()
        }
    }

    init {
        if (url == null) {
            // 地址都拼不出来，说明 BASE_URL 本身有问题。不抛异常：
            // 抛出去会带走整个建房流程，而这里只是"公网通道不可用"，
            // 局域网直连照样能跑
            _alive.value = false
        } else {
            connect()
        }
    }

    private fun connect() {
        val target = url ?: return
        socket = client.newWebSocket(Request.Builder().url(target).build(), listener)
    }

    /**
     * 指数退避重连，封顶 15 秒。
     *
     * 不重连的话，用户切到后台再回来（系统掐了 socket）房间就"静默死亡"——
     * 界面上还在房间里，实际已经是单机播放，这种假在线比明确掉线更糟。
     */
    private fun reconnect() {
        if (closed) return
        scope.launch(Dispatchers.IO) {
            if (retries >= MAX_RETRIES) return@launch
            val wait = (1L shl retries.coerceAtMost(4)) * 1000L
            retries++
            delay(wait.coerceAtMost(15_000L))
            if (!closed && !scope.isActive) return@launch
            if (!closed) connect()
        }
    }

    override suspend fun broadcast(message: RoomMessage) {
        send(message)
    }

    override suspend fun shout(message: RoomMessage) {
        send(message)
    }

    override suspend fun sendTo(deviceId: String, message: RoomMessage) {
        // 中转由服务端做：带上 target，服务端只发给那一台
        send(message.copy(target = deviceId))
    }

    override fun lanPeers(): Map<String, String> = noPeers

    override fun close() {
        closed = true
        runCatching { socket?.close(NORMAL_CLOSE, "leave") }
        socket = null
        client.dispatcher.executorService.shutdown()
        _alive.value = false
    }

    private fun send(message: RoomMessage) {
        val text = String(RoomCodec.encode(message), Charsets.UTF_8)
        // 注意 Result<Boolean?>.getOrDefault 会保持可空类型，必须用 getOrNull 兜一次
        val sent = runCatching { socket?.send(text) }.getOrNull() ?: false
        if (!sent) {
            // 没发出去说明连接断了；不在这里重试，重新连上后上层会补一次全量状态
            _alive.value = false
        }
    }

    /**
     * 构建 WebSocket 地址。
     *
     * **不能用 `builder.scheme("ws")`**：`HttpUrl.Builder.scheme()` 的 setter 只认
     * http/https，传 ws 会直接抛 `IllegalArgumentException: unexpected scheme: ws`。
     * 但 `HttpUrl.parse` 是认 ws/wss 的（内部映射成 http/https）——
     * 所以这里先用 http 构建出完整地址，再把前缀替换掉，交给
     * `Request.Builder().url()` 去解析。顺序反了就是启动即崩。
     */
    private fun buildUrl(): String? {
        val http = baseUrl.toHttpUrlOrNull() ?: return null
        val target = runCatching {
            http.newBuilder()
                .addPathSegments("app/music/room/ws/${roomCode.trim()}")
                .addQueryParameter("deviceId", myId)
                .addQueryParameter("nickname", nickname)
                .build()
        }.getOrNull() ?: return null
        val text = target.toString()
        return when {
            text.startsWith("https://") -> "wss://" + text.removePrefix("https://")
            text.startsWith("http://") -> "ws://" + text.removePrefix("http://")
            else -> null
        }
    }

    private companion object {
        const val PING_SECONDS = 15L
        const val NORMAL_CLOSE = 1000
        const val MAX_RETRIES = 6
    }
}
