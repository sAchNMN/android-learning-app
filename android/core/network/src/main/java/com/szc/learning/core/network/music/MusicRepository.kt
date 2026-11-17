package com.szc.learning.core.network.music

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.map
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.callData

/**
 * 云端曲库仓库：把接口 DTO 翻译成 UI 用的 [MusicTrack]。
 *
 * `url` 可能是完整地址、也可能是 `/profile/music/01.mp3` 这种站内相对路径 ——
 * 后端刻意只存相对路径，所以这里必须补成绝对地址再交给 ExoPlayer。
 * 原样透传会被 ExoPlayer 当成**本地文件路径**，直接 FileNotFound。
 * `url` 为空时才回退到按 id 拼 `stream/{id}`。
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
        uri = url?.takeIf { it.isNotBlank() }?.let { absolute(it) }
            ?: (normalizedBase() + STREAM_PATH + id),
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
