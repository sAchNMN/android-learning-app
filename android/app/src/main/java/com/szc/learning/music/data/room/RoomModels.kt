package com.szc.learning.music.data.room

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.google.gson.Gson

/**
 * 一起听的同步模式。
 *
 * [LAN] 不是「猜」出来的：必须子网广播探测收到全员 ACK 才判定，
 * 否则同一个公司网里两个不同网段的设备会被误判，一开播就是各唱各的。
 */
enum class SyncMode { LAN, WAN }

/**
 * 房间消息。
 *
 * 刻意做成**单个扁平类 + 可空字段**，而不是 sealed class 多态：
 * Gson 不支持多态反序列化，sealed 那些 `@SerializedName("type")` 的写法在
 * 混淆后还会踩到内部类名，反而更脆。扁平结构多出来的空字段代价是几十字节，
 * 换来的是收发两端都不需要注册类型适配器。
 *
 * **幂等原则**：[STATE] 是状态快照而不是命令。丢一条没关系，
 * 下一条心跳就把它纠正过来。所有同步逻辑都建立在这个前提上。
 */
data class RoomMessage(
    val type: String,
    /** 发起方 deviceId。收到自己的消息直接丢弃 —— 这是唯一的回声抑制手段。 */
    val from: String = "",
    /** 单调递增，用于丢弃乱序到达的旧状态。 */
    val seq: Long = 0,

    // ---- HELLO / 成员信息 ----
    val nickname: String? = null,
    /** 本机网段，形如 `192.168.1.0/24`。用于初筛是否可能同网。 */
    val subnet: String? = null,
    val gateway: String? = null,
    val lanPort: Int = 0,
    val host: Boolean = false,

    // ---- PROBE ----
    /** 探测目标 deviceId（PROBE 里为空表示广播给所有人）。 */
    val target: String? = null,
    val probeRound: Long = 0,

    // ---- TIME ----
    val t1: Long = 0,
    val t2: Long = 0,
    val t3: Long = 0,

    // ---- STATE ----
    val songKey: String? = null,
    val track: TrackPayload? = null,
    val anchorPosMs: Long = 0,
    /** 房主时钟（elapsedRealtime 基准）上锚点对应的时刻。 */
    val anchorHostMs: Long = 0,
    val playing: Boolean = false,
    /**
     * 预定起播时刻（房主时钟）。大于 0 表示"到点一起响"，
     * 各端本地准备好后统一等待到这个时刻再 play —— 这是切歌不打架的关键。
     */
    val startAtHostMs: Long = 0,

    // ---- MEMBERS / MODE ----
    val members: List<MemberInfo>? = null,
    val mode: String? = null,
    /** 判定结果里给人看的一句话（"同网段 6 台，探测 4/4 通"）。 */
    val reason: String? = null,
) {
    companion object {
        const val HELLO = "hello"
        const val PROBE = "probe"
        const val PROBE_ACK = "probe_ack"
        const val TIME_REQ = "time_req"
        const val TIME_ACK = "time_ack"
        const val STATE = "state"
        const val MEMBERS = "members"
        const val MODE = "mode"
        const val LEAVE = "leave"
        const val PING = "ping"
    }
}

/** 成员快照。`lanOk` 只有在子网探测回 ACK 后才为 true。 */
data class MemberInfo(
    val deviceId: String = "",
    val nickname: String = "",
    val host: Boolean = false,
    val subnet: String? = null,
    val gateway: String? = null,
    val lanIp: String? = null,
    val lanPort: Int = 0,
    val lanOk: Boolean = false,
    /** 与房主的单程延迟估计（毫秒），LAN 下通常个位数。 */
    val rttMs: Long = 0,
)

/**
 * 曲目在网络上传输的形态。
 *
 * 为什么不直接传 [MusicTrack]：那个类在 core:common 里，加字段就会动到
 * 所有端。这里的字段是**协议**，改动要兼容旧版本 —— 显式列出来才有约束力。
 *
 * [uri] 会带过去：网易云/酷狗的直链是 CDN 地址，不绑 IP 也不绑设备，
 * 对端拿到就能播，省掉一次取址往返。取不到时对端会自己重新解析。
 */
data class TrackPayload(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val uri: String = "",
    val artUri: String? = null,
    val source: String = "ONLINE",
    val sourceKey: String = "",
    val hash: String = "",
    val albumId: String = "",
    val albumAudioId: String = "",
) {
    fun toTrack(): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = uri,
        artUri = artUri,
        source = runCatching { TrackSource.valueOf(source) }.getOrDefault(TrackSource.ONLINE),
        sourceKey = sourceKey,
        hash = hash,
        albumId = albumId,
        albumAudioId = albumAudioId,
    )

    companion object {
        fun of(track: MusicTrack): TrackPayload = TrackPayload(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMs = track.durationMs,
            uri = track.uri,
            artUri = track.artUri,
            source = track.source.name,
            sourceKey = track.sourceKey,
            hash = track.hash,
            albumId = track.albumId,
            albumAudioId = track.albumAudioId,
        )
    }
}

/** 协议编解码。失败返回 null 而不是抛异常 —— 一条坏包不该有能力弄断房间。 */
internal object RoomCodec {

    private val gson = Gson()

    fun encode(message: RoomMessage): ByteArray =
        gson.toJson(message).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray, length: Int = bytes.size): RoomMessage? = runCatching {
        val text = String(bytes, 0, length, Charsets.UTF_8)
        val message = gson.fromJson(text, RoomMessage::class.java)
        // type 空的包一律当坏包：没有类型就不知道该往哪儿分发
        message.takeIf { !it.type.isNullOrBlank() }
    }.getOrNull()

    fun decode(text: String): RoomMessage? = runCatching {
        gson.fromJson(text, RoomMessage::class.java)?.takeIf { !it.type.isNullOrBlank() }
    }.getOrNull()
}
