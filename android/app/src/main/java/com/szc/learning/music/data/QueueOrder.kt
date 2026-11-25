package com.szc.learning.music.data

import com.szc.learning.core.common.music.MusicTrack

/**
 * 队列重排落到 feed 上的纯计算。
 *
 * 播放器的时间线和播放页的 feed 是两个列表：队列是 media3 的顺序，
 * feed 是 ViewPager 的数据源。用户在队列里拖动一行，两边都要跟着动，
 * 否则翻页会翻到和实际播放对不上的歌。
 *
 * 按 **key** 定位而不是按索引：feed 里可能有尚未换到播放地址的曲目
 * （它们没进队列），两边索引天然不同。按 key 改，多出来的那些原地不动。
 */
object QueueOrder {

    /**
     * 把 [movedKey] 移到 [anchorKey] 的前/后。
     *
     * 语义对齐 media3 的 `moveMediaItem(from, to)`：`toLater = to > from` 时
     * 插在锚点后面，反之插在前面 —— 因为锚点在移除 moved 之后索引会前移一格，
     * 不区分方向就会差一首。
     *
     * 找不到 moved 就原样返回；找不到锚点（feed 里没有这首）退回末尾，
     * 至少不让这首从列表里消失。
     */
    fun move(
        feed: List<MusicTrack>,
        movedKey: String,
        anchorKey: String,
        toLater: Boolean,
    ): List<MusicTrack> {
        val result = feed.toMutableList()
        val from = result.indexOfFirst { it.key == movedKey }
        if (from < 0) return feed
        val moved = result.removeAt(from)
        val anchor = result.indexOfFirst { it.key == anchorKey }
        result.add(if (anchor < 0) result.size else if (toLater) anchor + 1 else anchor, moved)
        return result
    }
}
