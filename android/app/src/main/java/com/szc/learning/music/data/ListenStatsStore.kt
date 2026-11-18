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