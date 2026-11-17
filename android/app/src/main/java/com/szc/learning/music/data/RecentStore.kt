package com.szc.learning.music.data

import android.content.Context
import com.szc.learning.core.common.music.MusicTrack

/**
 * 最近播放列表。
 *
 * 只存曲目的稳定键（`LOCAL:123` / `CLOUD:45`），不存整条数据 ——
 * 曲目元信息可能被用户改（重命名文件、后端换封面），
 * 存键、显示时再回表查，永远显示最新的。
 */
class RecentStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(track: MusicTrack) {
        val keys = keys().toMutableList()
        keys.remove(track.key)
        keys.add(0, track.key)
        while (keys.size > LIMIT) keys.removeAt(keys.lastIndex)
        prefs.edit().putString(KEY_LIST, keys.joinToString(SEPARATOR)).apply()
    }

    fun keys(): List<String> =
        prefs.getString(KEY_LIST, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    /** 按历史顺序把键映射回曲目；查不到的（文件被删）直接跳过。 */
    fun resolve(pool: List<MusicTrack>): List<MusicTrack> {
        val byKey = pool.associateBy { it.key }
        return keys().mapNotNull { byKey[it] }
    }

    private companion object {
        const val PREFS = "aurora_recent"
        const val KEY_LIST = "keys"
        const val SEPARATOR = "\n"
        const val LIMIT = 12
    }
}
