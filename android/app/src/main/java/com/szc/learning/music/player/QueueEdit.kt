package com.szc.learning.music.player

/**
 * 队列编辑的纯计算。
 *
 * 抽出来是为了可测：跨进程的 `removeMediaItem` 一条一条发，千首队列会产生
 * 上千次 IPC 和整队序列化；这里只负责把要删的索引合并成连续区间，
 * 调用方从后往前按区间删，索引不会互相错位。
 */
internal object QueueEdit {

    /**
     * 升序、互不相邻的连续区间。
     *
     * 例：[1, 2, 3, 7, 9, 10] → [1..3, 7..7, 9..10]
     */
    fun ranges(indices: Collection<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val sorted = indices.sorted()
        val out = ArrayList<IntRange>()
        var start = sorted[0]
        var end = sorted[0]
        for (index in sorted.drop(1)) {
            if (index == end + 1) {
                end = index
            } else if (index != end) {
                out += start..end
                start = index
                end = index
            }
        }
        out += start..end
        return out
    }
}
