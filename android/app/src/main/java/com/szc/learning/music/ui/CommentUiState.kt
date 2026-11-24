package com.szc.learning.music.ui

import com.szc.learning.core.network.music.MusicComment

/**
 * 一首歌的评论弹层状态。
 *
 * [error] 和 [items] 可以同时非空：翻页失败时列表不该被清空 ——
 * 已加载的评论还在，只是多了一条「加载失败」。UI 按「items 非空就渲染列表，
 * error 非空就在底部加一行提示」处理。
 */
data class CommentUiState(
    val loading: Boolean = false,
    /** 发表中：禁用发送按钮，避免连点发出两条一样的。 */
    val posting: Boolean = false,
    val items: List<MusicComment> = emptyList(),
    val total: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
) {
    /** 真正空（加载完、没数据、没错误）才显示空态，避免加载中和空态闪一下。 */
    val isEmpty: Boolean get() = !loading && items.isEmpty() && error == null
}
