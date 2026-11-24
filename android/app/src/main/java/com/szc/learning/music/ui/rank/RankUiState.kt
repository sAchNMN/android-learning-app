package com.szc.learning.music.ui.rank

import com.szc.learning.core.network.music.RankedTrack

/** 两种榜单。字符串直接就是后端 `type` 参数，省一层映射。 */
object RankType {
    const val HOT = "hot"
    const val RISING = "rising"
}

/**
 * 榜单页的状态。
 *
 * [message] 非空且 [tracks] 为空，就是"查不了"：后端不在、或者事件表没建。
 * 这两种情况页面显示的文字不同，所以不合并成一个 `error` 布尔。
 */
data class RankUiState(
    val type: String = RankType.HOT,
    val loading: Boolean = false,
    val tracks: List<RankedTrack> = emptyList(),
    val eventCount: Long = 0,
    val windowStart: String? = null,
    val updatedAt: String? = null,
    val message: String? = null,
)
