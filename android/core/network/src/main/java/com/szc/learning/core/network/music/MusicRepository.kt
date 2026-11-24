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

    /**
     * 聚合搜索。失败（后端不可达 / 没开音源）返回 [AppResult.Failure]；
     * 单个平台失败只是 `errors` 里的一条，不影响整体成功。
     */
    suspend fun searchOnline(
        keyword: String,
        limit: Int = 20,
        cookies: String? = null,
    ): AppResult<OnlineSearchResult> =
        callData { api.searchOnline(keyword, limit, cookies = cookies) }.map { dto ->
            OnlineSearchResult(
                tracks = dto.tracks.mapNotNull { it.toOnlineTrack() },
                errors = dto.errors,
            )
        }

    /**
     * 换取播放地址。`fallback = true` 时后端会在当前平台不可播时自动换源，
     * 返回的 [OnlinePlayback.switchedFrom] / [OnlinePlayback.switchedTo] 非空即发生了换源。
     */
    suspend fun playOnline(
        track: MusicTrack,
        fallback: Boolean = true,
        cookies: String? = null,
    ): AppResult<OnlinePlayback> =
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
                cookies = cookies,
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
    suspend fun onlineLyric(track: MusicTrack, cookies: String? = null): String? {
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
                cookies = cookies,
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

    // ---------------------------------------------------------------- 我喜欢

    /**
     * 同步「喜欢 / 取消喜欢」。
     *
     * 后端先落库再推平台，`saved=false` 但请求成功也是可能的（表没建 / 库挂了），
     * 所以调用方要同时看 [LikeSyncResult.saved] 和 [LikeSyncResult.syncs]。
     */
    suspend fun setLike(
        deviceId: String,
        track: MusicTrack,
        liked: Boolean,
        cookies: String? = null,
    ): AppResult<LikeSyncResult> =
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
                cookies = cookies,
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

    // ---------------------------------------------------------------- 歌单分享码

    /**
     * 生成分享码。
     *
     * 本地曲目会被静默过滤掉（`toShareDto` 保留，但在提交前剔除）：
     * `content://` 指向的是本机文件，对方拿到只会是一首点开就报错的歌。
     * 过滤放在这里而不是 UI —— 每个调用点各写一遍迟早会漏。
     */
    suspend fun createShare(
        deviceId: String,
        title: String,
        tracks: List<MusicTrack>,
    ): AppResult<ShareResult> {
        val shareable = tracks.filter { it.source != TrackSource.LOCAL }
        if (shareable.isEmpty()) {
            return AppResult.Failure("这个列表里没有可以分享的曲目（本地文件对方播不了）")
        }
        return callData {
            api.createShare(
                ShareRequestDto(
                    deviceId = deviceId,
                    title = title,
                    tracks = shareable.map { it.toShareDto() },
                ),
            )
        }.map { it.toResult() }
    }

    /** 按码取歌单。曲目已转成可直接播放的模型，在线曲目的地址仍然点播时才换。 */
    suspend fun resolveShare(code: String): AppResult<SharedPlaylist> =
        callData { api.resolveShare(code) }.map { it.toPlaylist(baseUrl) }

    // ---------------------------------------------------------------- 歌曲评论

    /**
     * 评论列表。
     *
     * 定位用的是 `title + artist`，不是 `track.id`：评论属于「这首歌」，
     * 而 `id` 属于「某个平台上的一条曲目记录」。同一首歌换个音源听，
     * 评论应该还在。
     */
    suspend fun comments(
        track: MusicTrack,
        deviceId: String,
        limit: Int = 20,
        offset: Int = 0,
    ): AppResult<CommentPage> = callData {
        api.comments(
            title = track.title,
            artist = track.artist,
            deviceId = deviceId,
            limit = limit,
            offset = offset,
        )
    }.map { it.toPage() }

    /**
     * 发表评论。
     *
     * [nickname] 为空时后端会存空串，列表里显示成「匿名听众」——
     * 匿名是刻意的：App 没有登录体系，强行要昵称只会把人劝退。
     */
    suspend fun createComment(
        track: MusicTrack,
        deviceId: String,
        nickname: String,
        content: String,
    ): AppResult<MusicComment> = callData {
        api.createComment(
            CommentBodyDto(
                deviceId = deviceId,
                deviceName = nickname,
                title = track.title,
                artist = track.artist,
                sourceKey = track.sourceKey.ifBlank { track.source.name.lowercase() },
                trackId = track.id,
                content = content,
            ),
        )
    }.map { it.toComment() }

    suspend fun likeComment(
        commentId: Long,
        deviceId: String,
        liked: Boolean,
    ): AppResult<CommentLikeResult> = callData {
        api.likeComment(commentId, CommentLikeBodyDto(deviceId = deviceId, liked = liked))
    }.map { CommentLikeResult(liked = it.liked, likeCount = it.likeCount) }

    suspend fun deleteComment(commentId: Long, deviceId: String): AppResult<Unit> =
        callUnit { api.deleteComment(commentId, deviceId) }

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