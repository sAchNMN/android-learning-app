package com.szc.learning.core.network.music

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.map
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.callData
import com.szc.learning.core.network.callUnit

/**
 * 云端曲库 + 在线音源仓库。
 *
 * 职责边界：
 * - 把接口 DTO 翻译成 UI 用的 [MusicTrack]
 * - 在线曲目在搜索阶段**没有**播放地址，`uri` 为空；点播时才由 [playOnline] 换取
 * - 本仓库不做任何平台判断，换源完全由后端完成
 *
 * `url` 可能是完整地址、也可能是 `/profile/music/01.mp3` 这种站内相对路径 ——
 * 后端刻意只存相对路径，所以这里必须补成绝对地址再交给 ExoPlayer。
 * 原样透传会被 ExoPlayer 当成**本地文件路径**，直接 FileNotFound。
 */
class MusicRepository(
    private val api: MusicApi,
    private val baseUrl: String,
) {

    suspend fun tracks(): AppResult<List<MusicTrack>> =
        callData { api.tracks() }.map { list -> list.map { it.toTrack() } }

    /**
     * 聚合搜索。失败（后端不可达 / 没开音源）返回 [AppResult.Failure]；
     * 单个平台失败只是 `errors` 里的一条，不影响整体成功。
     */
    suspend fun searchOnline(keyword: String, limit: Int = 20): AppResult<OnlineSearchResult> =
        callData { api.searchOnline(keyword, limit) }.map { dto ->
            OnlineSearchResult(
                tracks = dto.tracks.mapNotNull { it.toOnlineTrack() },
                errors = dto.errors,
            )
        }

    /**
     * 换取播放地址。`fallback = true` 时后端会在当前平台不可播时自动换源，
     * 返回的 [OnlinePlayback.switchedFrom] / [OnlinePlayback.switchedTo] 非空即发生了换源。
     */
    suspend fun playOnline(track: MusicTrack, fallback: Boolean = true): AppResult<OnlinePlayback> =
        callData {
            api.playOnline(
                source = track.sourceKey,
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                durationMs = track.durationMs.takeIf { it > 0 },
                hash = track.hash.takeIf { it.isNotBlank() },
                albumId = track.albumId.takeIf { it.isNotBlank() },
                albumAudioId = track.albumAudioId.takeIf { it.isNotBlank() },
                quality = "exhigh",
                fallback = fallback,
            )
        }.map { dto ->
            val url = dto.url
            if (dto.playable && !url.isNullOrBlank()) {
                OnlinePlayback(
                    track = track.copy(
                        // 换源后 id/source 会变，必须跟着改，否则收藏键、歌词请求都会指向原平台
                        id = dto.id.ifBlank { track.id },
                        sourceKey = dto.source.ifBlank { track.sourceKey },
                        uri = url,
                        playable = true,
                    ),
                    switchedFrom = dto.switchedFrom,
                    switchedTo = dto.switchedTo,
                )
            } else {
                OnlinePlayback(
                    track = null,
                    message = dto.message?.takeIf { it.isNotBlank() } ?: DEFAULT_UNPLAYABLE,
                    switchedFrom = dto.switchedFrom,
                    switchedTo = dto.switchedTo,
                )
            }
        }

    /** 在线歌词。拿不到就返回 null，不把「没歌词」当错误弹给用户。 */
    suspend fun onlineLyric(track: MusicTrack): String? {
        if (track.source != TrackSource.ONLINE) return null
        val result = callData {
            api.onlineLyric(
                source = track.sourceKey,
                id = track.id,
                hash = track.hash.takeIf { it.isNotBlank() },
                albumId = track.albumId.takeIf { it.isNotBlank() },
                albumAudioId = track.albumAudioId.takeIf { it.isNotBlank() },
                durationMs = track.durationMs.takeIf { it > 0 },
                title = track.title,
                artist = track.artist,
            )
        }
        return when (result) {
            is AppResult.Success -> result.data.takeIf { it.isNotBlank() }
            is AppResult.Failure -> null
        }
    }

    suspend fun sources(): AppResult<List<MusicSourceInfo>> =
        callData { api.sources() }.map { list -> list.map { it.toInfo() } }

    suspend fun updateSource(
        key: String,
        enabled: Boolean? = null,
        priority: Int? = null,
        cookie: String? = null,
    ): AppResult<Unit> = callUnit {
        api.updateSource(key, SourceUpdateBody(enabled = enabled, priority = priority, cookie = cookie))
    }

    private fun MusicTrackDto.toTrack(): MusicTrack = MusicTrack(
        id = id.toString(),
        title = title.ifBlank { "未命名" },
        artist = artist,
        album = album,
        durationMs = durationMs,
        uri = url?.takeIf { it.isNotBlank() }?.let { absolute(it) }
            ?: (normalizedBase() + STREAM_PATH + id),
        artUri = cover?.takeIf { it.isNotBlank() }?.let { absolute(it) },
        lyrics = lyrics?.takeIf { it.isNotBlank() },
        source = TrackSource.CLOUD,
    )

    private fun OnlineTrackDto.toOnlineTrack(): MusicTrack? {
        if (id.isBlank() || title.isBlank()) return null
        return MusicTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            // 搜索阶段还没有播放地址，点播时再填
            uri = "",
            artUri = coverUrl?.takeIf { it.isNotBlank() },
            source = TrackSource.ONLINE,
            sourceKey = source,
            hash = hash.orEmpty(),
            albumId = albumId.orEmpty(),
            albumAudioId = albumAudioId.orEmpty(),
            playable = playable ?: true,
        )
    }

    private fun normalizedBase(): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    /** 后端可能下发 `/upload/xx.jpg` 这种站内相对路径，补上 host 才能直接加载。 */
    private fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> normalizedBase().trimEnd('/') + path
        else -> normalizedBase() + path
    }

    private companion object {
        const val STREAM_PATH = "app/music/stream/"
        const val DEFAULT_UNPLAYABLE = "这首歌当前不可播放"
    }
}