package com.szc.learning.music.player

/**
 * AB 段循环区间。
 *
 * [startMs] 含着、[endMs] 不含 —— 播放到 endMs 就跳回 startMs。
 * 用毫秒而不是时间码字符串：进度条、ticker、seek 全是毫秒，
 * 在边界处转格式只会多一次取整误差。
 */
data class LoopRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}
