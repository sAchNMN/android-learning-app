package com.szc.learning.music.data

import android.content.Context
import com.szc.learning.core.common.music.MusicTrack

/**
 * 我喜欢的歌。
 *
 * 和 [RecentStore] 一样只存稳定键（`LOCAL:123` / `CLOUD:45`），
 * 展示时回表查当前元信息。SharedPreferences 的 StringSet 足够，
 * 不值得为几十条键开 DataStore。
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun keys(): Set<String> =
        prefs.getStringSet(KEY_KEYS, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(key: String): Boolean = key in keys()

    /** 返回切换后的状态：true = 已收藏。 */
    fun toggle(key: String): Boolean {
        val current = keys().toMutableSet()
        val nowFavorite = if (current.contains(key)) {
            current.remove(key)
            false
        } else {
            current.add(key)
            true
        }
        // SharedPreferences 不会拷贝传入的 Set，必须交一个快照
        prefs.edit().putStringSet(KEY_KEYS, current).apply()
        return nowFavorite
    }

    /** 按历史顺序把键映射回曲目；查不到的（文件被删）直接跳过。 */
    fun resolve(pool: List<MusicTrack>): List<MusicTrack> {
        val byKey = pool.associateBy { it.key }
        return keys().mapNotNull { byKey[it] }
    }

    private companion object {
        const val PREFS = "aurora_favorites"
        const val KEY_KEYS = "keys"
    }
}