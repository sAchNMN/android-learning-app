package com.szc.learning.core.common.music

/**
 * 曲目来源。
 *
 * - LOCAL：MediaStore 扫出来的本地文件
 * - CLOUD：上传到本站 `/profile/music/` 的 mp3
 * - ONLINE：网易云 / 酷狗等第三方音源，播放地址是**点击时**才向服务端换来的
 */
enum class TrackSource { LOCAL, CLOUD, ONLINE }

/**
 * 播放器统一曲目模型。
 *
 * 本地曲目：uri 是 `content://media/external/audio/media/{id}`
 * 云端曲目：uri 是 `http(s)://host/app/music/stream/{id}`，ExoPlayer 直接流式拉取
 * 在线曲目：搜索结果的 uri 为空，点播时由服务端 `/app/music/play` 换取真实地址，
 * 再把 [uri] 填上交给播放器。
 *
 * 两个来源共用一套 UI，所以不要把 MediaStore 字段（_ID、ALBUM_ID 之类）泄露到这一层，
 * 否则 RecyclerView 里就要写 if (source == LOCAL) 了。
 */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val artUri: String? = null,
    /** 内嵌歌词（云端接口可能直接下发；本地曲目由 LyricsRepository 按需解析 ID3）。 */
    val lyrics: String? = null,
    val source: TrackSource = TrackSource.LOCAL,
    /** 本地曲目的上级目录名（"Music"、"Download" …），用于资料库的文件夹视图。 */
    val folder: String = "",
    /** 在线音源标识（netease / kugou），本地与云端为空。 */
    val sourceKey: String = "",
    /** 酷狗取址参数；其它平台为空。 */
    val hash: String = "",
    val albumId: String = "",
    val albumAudioId: String = "",
    /** 平台标记的可播性，仅用于列表提示；真实可播性以取址结果为准。 */
    val playable: Boolean = true,
) {
    /**
     * 跨来源唯一键，用于播放队列比对、封面生成种子。
     *
     * 在线曲目必须把平台拼进去：网易云和酷狗的 id 可能撞车，
     * 只写 `ONLINE:123` 会让两首歌的收藏/最近播放互相覆盖。
     *
     * 结果缓存在字段里：DiffUtil、队列比对、封面种子这些循环里会反复访问同一个对象，
     * 每次拼字符串纯属浪费。**不能**用 `by lazy` 或属性初始化器 —— Gson 反序列化
     * 不走构造器，会拿到 null；这里用可空字段 + 首次访问填充。
     */
    @Transient
    private var cachedKey: String? = null

    val key: String
        get() {
            cachedKey?.let { return it }
            val value = if (source == TrackSource.ONLINE) {
                "ONLINE:$sourceKey:$id"
            } else {
                "${source.name}:$id"
            }
            cachedKey = value
            return value
        }
}

/** 毫秒 → `m:ss`；超过一小时才补小时位。 */
fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}