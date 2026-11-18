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

internal fun MusicSourceDto.toInfo(): MusicSourceInfo = MusicSourceInfo(
    key = key,
    label = label,
    enabled = enabled,
    priority = priority,
    hasCookie = hasCookie,
)