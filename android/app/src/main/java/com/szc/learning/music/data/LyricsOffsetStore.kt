package com.szc.learning.music.data

import android.content.Context

/**
 * 歌词校准偏移，按曲目存。
 *
 * 为什么需要：本地 mp3 的 ID3 歌词和音频头之间常有一段静音偏移，转码工具
 * 也常把时间轴整体挪 1~3 秒。这不是「歌词文件错了」，而是这一份文件在
 * 这一台机器上对不齐，所以修正只能是**本机、按曲目**的，不能写回文件。
 *
 * 存 SharedPreferences 而不是文件：一条记录就是一个 Long，几十上百首也就几 KB，
 * 和收藏、音源配置一样用最轻的存法，不引新依赖。
 *
 * key 直接当 preference key：曲目 key 形如 `ONLINE:netease:123`，
 * SharedPreferences 允许冒号，不需要再做一层转义。
 */
class LyricsOffsetStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun offsetOf(key: String): Long = prefs.getLong(key, 0L).coerceIn(-MAX_MS, MAX_MS)

    /** 步进校准，返回新值。正数让歌词出现得更晚（时间轴整体后移）。 */
    fun shift(key: String, deltaMs: Long): Long {
        val next = (offsetOf(key) + deltaMs).coerceIn(-MAX_MS, MAX_MS)
        prefs.edit().putLong(key, next).apply()
        return next
    }

    fun reset(key: String): Long {
        prefs.edit().remove(key).apply()
        return 0L
    }

    private companion object {
        const val PREFS = "aurora_lyrics_offset"

        /** ±10 秒。再大的偏差说明歌词根本不是这首歌的，调也没用。 */
        const val MAX_MS = 10_000L
    }
}
