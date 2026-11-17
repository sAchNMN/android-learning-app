package com.szc.learning.core.network.music

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.map
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.callData

/**
 * 云端曲库仓库：把接口 DTO 翻译成 UI 用的 [MusicTrack]。
 *
 * 需要 baseUrl 是因为后端允许只下发 id，播放地址由客户端拼 `stream/{id}`。
 * 这样 CDN 换域名不用改库里的数据，符合"数据不存绝对地址"的一般做法。
 */
class MusicRepository(
    private val api: MusicApi,
    private val baseUrl: String,
) {

    suspend fun tracks(): AppResult<List<MusicTrack>> =
        callData { api.tracks() }.map { list -> list.map { it.toTrack() } }

    private fun MusicTrackDto.toTrack(): MusicTrack = MusicTrack(
        id = id.toString(),
        title = title.ifBlank { "未命名" },
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = url?.takeIf { it.isNotBlank() } ?: (normalizedBase() + STREAM_PATH + id),
        artUri = cover?.takeIf { it.isNotBlank() }?.let { absolute(it) },
        source = TrackSource.CLOUD,
    )

    private fun normalizedBase(): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** 后端可能下发 `/upload/xx.jpg` 这种站内相对路径，补上 host 才能直接加载。 */
    private fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> normalizedBase().trimEnd('/') + path
        else -> normalizedBase() + path
    }

    private companion object {
        const val STREAM_PATH = "app/music/stream/"
    }
}
