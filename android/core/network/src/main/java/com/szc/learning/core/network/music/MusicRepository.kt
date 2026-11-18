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

    // ---------------------------------------------------------------- 推荐

    /**
     * 猜你喜欢。
     *
     * 口味歌手由调用方（ViewModel）从收藏 + 最近播放里算，服务端只负责「按歌手找歌 + 补发现流」，
     * 这样推荐结果的解释权在客户端，后端不必知道用户的一切。
     */
    suspend fun recommend(
        deviceId: String?,
        artists: List<String>,
        seed: Long? = null,
        limit: Int = 20,
    ): AppResult<RecommendResult> =
        callData {
            api.recommend(
                deviceId = deviceId,
                artists = artists.takeIf { it.isNotEmpty() }?.joinToString(","),
                seed = seed,
                limit = limit,
            )
        }.map { dto ->
            RecommendResult(
                tracks = dto.tracks.mapNotNull { it.toOnlineTrack() },
                basedOn = dto.basedOn,
                reason = dto.reason,
            )
        }

    // ---------------------------------------------------------------- 我喜欢

    /**
     * 同步「喜欢 / 取消喜欢」。
     *
     * 后端先落库再推平台，`saved=false` 但请求成功也是可能的（表没建 / 库挂了），
     * 所以调用方要同时看 [LikeSyncResult.saved] 和 [LikeSyncResult.syncs]。
     */
    suspend fun setLike(deviceId: String, track: MusicTrack, liked: Boolean): AppResult<LikeSyncResult> =
        callData {
            api.setLike(
                LikeBody(
                    deviceId = deviceId,
                    source = track.likeSource(),
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    cover = track.artUri,
                    hash = track.hash,
                    albumId = track.albumId,
                    albumAudioId = track.albumAudioId,
                    liked = liked,
                ),
            )
        }.map { dto ->
            LikeSyncResult(
                liked = dto.liked,
                saved = dto.saved,
                syncs = dto.syncs.map { LikeSync(it.source, it.ok, it.message) },
            )
        }

    /** 我喜欢的曲目：重装 / 换设备后靠它恢复，也是推荐的口味来源。 */
    suspend fun likes(deviceId: String, limit: Int = 200): AppResult<List<MusicTrack>> =
        callData { api.likes(deviceId, limit) }.map { list -> list.mapNotNull { it.toTrack() } }

    // ---------------------------------------------------------------- 个人歌单

    suspend fun userPlaylists(): AppResult<List<UserPlaylistInfo>> =
        callData { api.playlists() }.map { list ->
            list.map {
                UserPlaylistInfo(
                    source = it.source,
                    id = it.id,
                    name = it.name.ifBlank { "未命名歌单" },
                    coverUrl = it.coverUrl,
                    trackCount = it.trackCount,
                    own = it.own ?: true,
                )
            }
        }

    suspend fun playlistTracks(source: String, id: String, name: String): AppResult<List<MusicTrack>> =
        callData { api.playlistTracks(source, id, name) }
            .map { dto -> dto.tracks.mapNotNull { it.toOnlineTrack() } }

    /** 收藏时上报的 source：在线用平台名，本机用 local / cloud。 */
    private fun MusicTrack.likeSource(): String = when (source) {
        TrackSource.ONLINE -> sourceKey.ifBlank { "online" }
        TrackSource.CLOUD -> "cloud"
        TrackSource.LOCAL -> "local"
    }

    /** 后端存的喜欢记录 → 可播放曲目。在线曲目 uri 留空，点播时再换地址。 */
    private fun UserLikeDto.toTrack(): MusicTrack? {
        if (id.isBlank() || title.isBlank()) return null
        val trackSource = when (source.lowercase()) {
            "cloud" -> TrackSource.CLOUD
            "local" -> TrackSource.LOCAL
            else -> TrackSource.ONLINE
        }
        val trackUri = when (trackSource) {
            TrackSource.ONLINE -> ""
            TrackSource.CLOUD -> normalizedBase() + STREAM_PATH + id
            TrackSource.LOCAL -> "content://media/external/audio/media/$id"
        }
        return MusicTrack(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            uri = trackUri,
            artUri = cover?.takeIf { it.isNotBlank() },
            source = trackSource,
            sourceKey = if (trackSource == TrackSource.ONLINE) source.lowercase() else "",
            hash = hash.orEmpty(),
            albumId = albumId.orEmpty(),
            albumAudioId = albumAudioId.orEmpty(),
        )
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