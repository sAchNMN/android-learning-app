package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack

/**
 * 我喜欢的歌。
 *
 * 存的是**完整曲目**，不是只有键。原因：推荐页里收藏的是在线曲目，
 * 它不在本地/云端曲库里，只有键的话下次打开根本还原不出标题、封面和取址参数。
 *
 * 与后端的关系：
 * - 本机写入先落盘（离线也能点红心），再异步推给后端和音源平台
 * - 后端返回的喜欢列表通过 [merge] 并回来，重装后能恢复
 * - 旧的纯键数据（`keys` StringSet）保留兼容：能匹配就继续显示，不会因为升级丢收藏
 */
class FavoritesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<MusicTrack>>() {}.type

    private val items: MutableList<MusicTrack> by lazy {
        runCatching {
            val json = prefs.getString(KEY_TRACKS, null)
            if (json.isNullOrBlank()) mutableListOf() else gson.fromJson<List<MusicTrack>>(json, type)
                ?.toMutableList() ?: mutableListOf()
        }.getOrDefault(mutableListOf())
    }

    /** 旧版本只存过键，这里只读不写，用来兜住升级前的数据。 */
    private val legacyKeys: Set<String>
        get() = prefs.getStringSet(KEY_LEGACY_KEYS, emptySet())?.toSet() ?: emptySet()

    @Synchronized
    fun tracks(): List<MusicTrack> = items.toList()

    @Synchronized
    fun keys(): Set<String> = items.map { it.key }.toSet() + legacyKeys

    @Synchronized
    fun isFavorite(key: String): Boolean = key in keys()

    /** 返回切换后的状态：true = 已收藏。 */
    @Synchronized
    fun toggle(track: MusicTrack): Boolean {
        val nowFavorite = if (items.any { it.key == track.key }) {
            items.removeAll { it.key == track.key }
            false
        } else {
            items.add(0, track.copy(lyrics = null))
            true
        }
        if (!nowFavorite && track.key in legacyKeys) {
            // 旧数据里只有键，删掉时也要把键清掉，否则 keys() 又会把它算回来
            prefs.edit().putStringSet(KEY_LEGACY_KEYS, legacyKeys - track.key).apply()
        }
        persist()
        return nowFavorite
    }

    /**
     * 把后端的喜欢列表并进来，返回新增条数。
     *
     * 只做「后端有、本机没有」的补写；本机有的以本机为准（可能刚点了取消、还没来得及同步）。
     */
    @Synchronized
    fun merge(remote: List<MusicTrack>): Int {
        val existing = items.map { it.key }.toSet()
        val added = remote.filter { it.key !in existing }
        if (added.isEmpty()) return 0
        items.addAll(added)
        persist()
        return added.size
    }

    private fun persist() {
        prefs.edit().putString(KEY_TRACKS, gson.toJson(items)).apply()
    }

    private companion object {
        const val PREFS = "aurora_favorites"
        const val KEY_TRACKS = "tracks_json"
        const val KEY_LEGACY_KEYS = "keys"
    }
}
