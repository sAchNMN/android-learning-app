package com.szc.learning.core.network.music

import com.szc.learning.core.common.music.MusicTrack

/**
 * 在线搜索的领域结果。
 *
 * `errors` 是「哪个平台没搜成功」的可读原因，UI 只在需要时提示，
 * 不影响其它平台已经返回的结果。
 */
data class OnlineSearchResult(
    val tracks: List<MusicTrack>,
    val errors: Map<String, String> = emptyMap(),
)

/**
 * 点播结果。
 *
 * `track == null` 表示当前不可播，`message` 是给用户看的原因
 * （需要登录 / 需要会员 / 版权限制 …）。
 */
data class OnlinePlayback(
    val track: MusicTrack?,
    val message: String? = null,
    val switchedFrom: String? = null,
    val switchedTo: String? = null,
)

/** 音源管理项，对应后端 `/app/music/sources`。 */
data class MusicSourceInfo(
    val key: String,
    val label: String,
    val enabled: Boolean,
    val priority: Int,
    val hasCookie: Boolean,
)

/**
 * 推荐结果。
 *
 * [reason] 说明口味从哪来：favorites（收藏里的歌手）/ likes（后台喜欢记录）/ discover（无偏好兜底）。
 * 页面把它翻译成一句话，用户才知道这批歌为什么长这样。
 */
data class RecommendResult(
    val tracks: List<MusicTrack>,
    val basedOn: List<String> = emptyList(),
    val reason: String = "discover",
)

/** 单平台同步回执。 */
data class LikeSync(
    val source: String,
    val ok: Boolean,
    val message: String,
)

/**
 * 一次收藏的完整结果。
 *
 * [saved] 是后端库写入结果，[syncs] 是各平台回执 —— 两者独立，
 * 「后端存了但网易云没登录」是正常状态，不该当成失败提示。
 */
data class LikeSyncResult(
    val liked: Boolean,
    val saved: Boolean,
    val syncs: List<LikeSync> = emptyList(),
)

/** 音源账号里的歌单。 */
data class UserPlaylistInfo(
    val source: String,
    val id: String,
    val name: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val own: Boolean = true,
)

internal fun MusicSourceDto.toInfo(): MusicSourceInfo = MusicSourceInfo(
    key = key,
    label = label,
    enabled = enabled,
    priority = priority,
    hasCookie = hasCookie,
)