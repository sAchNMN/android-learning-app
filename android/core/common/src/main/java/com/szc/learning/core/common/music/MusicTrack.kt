package com.szc.learning.core.common.music

/** 曲目来源。云端与本地在 UI 上共用同一个模型，只在播放地址和数据来源上分叉。 */
enum class TrackSource { LOCAL, CLOUD }

/**
 * 播放器统一曲目模型。
 *
 * 本地曲目：uri 是 `content://media/external/audio/media/{id}`
 * 云端曲目：uri 是 `http(s)://host/app/music/stream/{id}`，ExoPlayer 直接流式拉取
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
    val source: TrackSource = TrackSource.LOCAL,
) {
    /** 跨来源唯一键，用于播放队列比对、封面生成种子。 */
    val key: String get() = "${source.name}:$id"
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
