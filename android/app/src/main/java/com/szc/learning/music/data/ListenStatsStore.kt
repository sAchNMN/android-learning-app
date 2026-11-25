package com.szc.learning.music.data

import android.content.Context

/**
 * 听歌时长统计。
 *
 * 播放器每 350ms 推一次位置，这里只累计"进度继续往前走"的增量，
 * 拖动进度条造成的跳变（delta 为负或过大）直接丢弃，
 * 所以统计的是真实播放秒数，不是"打开过播放器"的时长。
 *
 * 每累计 10 秒落一次盘：既不会频繁写 SharedPreferences，
 * 进程被杀最多丢 10 秒，可以接受。
 *
 * **历史值不要校正**（2026-11-25 拍板，见 docs/PERF_DESIGN.md 第 5 节）。
 * 2026-11-25 之前，每个 Activity 各持一份 MusicViewModel，各自跑一份累计逻辑，
 * 数字等于「真实播放秒数 × 当时存活的 Activity 数」—— 开着沉浸播放页听歌是 2 倍。
 * P0-4 修掉重复累计后，[seconds] 里已有的值保持原样，不清零、不折半：
 * 历史倍率无从反推（1～3 之间浮动），折半要假设恒定 2 倍不准，清零更像新 bug。
 * 结果就是「总数偏高、增速正常」，这是已知且接受的状态。
 * 榜单不受影响：ListenEventReporter 是 AppContainer 单例，不随 VM 复制。
 */
class ListenStatsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seconds(): Long = prefs.getLong(KEY_SECONDS, 0L)

    fun minutes(): Long = seconds() / 60

    fun addSeconds(deltaSeconds: Long) {
        if (deltaSeconds <= 0) return
        prefs.edit().putLong(KEY_SECONDS, seconds() + deltaSeconds).apply()
    }

    private companion object {
        const val PREFS = "aurora_stats"
        const val KEY_SECONDS = "listen_seconds"
    }
}