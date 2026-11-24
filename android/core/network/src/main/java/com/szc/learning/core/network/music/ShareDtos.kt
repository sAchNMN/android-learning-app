package com.szc.learning.core.network.music

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource

/**
 * 歌单分享码的 DTO 与领域模型。
 *
 * **为什么曲目要用一份独立的扁平结构，而不是直接序列化 `MusicTrack`**：
 * 分享码是跨设备、跨版本的契约。`MusicTrack` 是 UI 模型，以后加字段（比如"音质偏好"）
 * 会改变序列化结果；这里把需要传的字段显式列出来，加字段时至少要想一次
 * 「对方的老版本能不能认」。
 *
 * `source` 用字符串（`ONLINE` / `CLOUD` / `LOCAL`）而不是枚举序号 ——
 * 枚举顺序一变，序号就错位，这是跨版本协议里最容易踩的坑。
 */
data class ShareRequestDto(
    val deviceId: String,
    val title: String,
    val tracks: List<ShareTrackDto>,
)

data class ShareTrackDto(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val artUri: String? = null,
    val source: String = "ONLINE",
    val sourceKey: String = "",
    /** 酷狗取址必需；酷我存的是 rid。 */
    val hash: String = "",
    val albumId: String = "",
    val albumAudioId: String = "",
)

data class ShareResultDto(
    val code: String = "",
    val title: String = "",
    val trackCount: Int = 0,
    val createTime: String? = null,
    val expireTime: String? = null,
    val viewCount: Int? = null,
    val tracks: List<ShareTrackDto> = emptyList(),
)

/** 一次分享的结果。 */
data class ShareResult(
    val code: String,
    val title: String,
    val trackCount: Int,
    val expireTime: String?,
    val viewCount: Int,
)

/** 导入结果：歌单名 + 可直接播放的曲目。 */
data class SharedPlaylist(
    val code: String,
    val title: String,
    val tracks: List<MusicTrack>,
)

// ---------------------------------------------------------------- 转换

internal fun MusicTrack.toShareDto(): ShareTrackDto = ShareTrackDto(
    id = id,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    artUri = artUri,
    source = source.name,
    sourceKey = sourceKey,
    hash = hash,
    albumId = albumId,
    albumAudioId = albumAudioId,
)

/**
 * 分享曲目 → 可播放曲目。
 *
 * 本地曲目会被丢弃（返回 null）：`content://` 指向的是**对方手机上的另一首歌**，
 * 分享过去只会得到一首点开就报错的歌。这一步不做过滤的话，导入方看到的列表
 * 会混进一堆无效项，而且没法从界面上分辨。
 */
internal fun ShareTrackDto.toSharedTrack(baseUrl: String): MusicTrack? {
    if (id.isBlank() || title.isBlank()) return null
    val trackSource = when (source.uppercase()) {
        "CLOUD" -> TrackSource.CLOUD
        "LOCAL" -> return null
        else -> TrackSource.ONLINE
    }
    return MusicTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        // 在线曲目点播时才换地址；云端曲目重新拼一次站内地址，不信任对方传来的绝对路径
        uri = if (trackSource == TrackSource.CLOUD) streamUrl(baseUrl, id) else "",
        artUri = artUri?.takeIf { it.isNotBlank() },
        source = trackSource,
        sourceKey = if (trackSource == TrackSource.ONLINE) sourceKey else "",
        hash = hash,
        albumId = albumId,
        albumAudioId = albumAudioId,
    )
}

private fun streamUrl(baseUrl: String, trackId: String): String {
    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return "${base}app/music/stream/$trackId"
}

internal fun ShareResultDto.toResult(): ShareResult = ShareResult(
    code = code,
    title = title.ifBlank { "未命名歌单" },
    trackCount = trackCount,
    expireTime = expireTime,
    viewCount = viewCount ?: 0,
)

internal fun ShareResultDto.toPlaylist(baseUrl: String): SharedPlaylist = SharedPlaylist(
    code = code,
    title = title.ifBlank { "分享的歌单" },
    tracks = tracks.mapNotNull { it.toSharedTrack(baseUrl) },
)
