package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.SharedPlaylist

/**
 * 导入进来的分享歌单，存在本机。
 *
 * 为什么不落后端：导入方的「我的歌单」列表属于个人数据，而且**必须在离线时可见** ——
 * 后端关掉就看不到自己导入过什么，这个体验说不过去。分享码本身在后端，
 * 但「我导入了哪些码」是本机事实。
 *
 * 同码只保留一份：重复导入同一个码不应该出现两张一样的卡片。
 * 再次导入会覆盖（可能是对方更新了歌单内容）。
 */
class SharedPlaylistStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<StoredPlaylist>>() {}.type

    private val items: MutableList<StoredPlaylist> by lazy { load() }

    /** 最近导入的在前。 */
    @Synchronized
    fun all(): List<StoredPlaylist> = items.sortedByDescending { it.importedAt }

    @Synchronized
    fun contains(code: String): Boolean = items.any { it.code.equals(code, ignoreCase = true) }

    /** 写入并返回是否为「新增」（false = 覆盖已有）。 */
    @Synchronized
    fun add(playlist: SharedPlaylist): Boolean {
        val existed = items.any { it.code.equals(playlist.code, ignoreCase = true) }
        items.removeAll { it.code.equals(playlist.code, ignoreCase = true) }
        items.add(
            0,
            StoredPlaylist(
                code = playlist.code,
                title = playlist.title,
                tracks = playlist.tracks,
                importedAt = System.currentTimeMillis(),
            ),
        )
        persist()
        return !existed
    }

    @Synchronized
    fun remove(code: String): Boolean {
        val removed = items.removeAll { it.code.equals(code, ignoreCase = true) }
        if (removed) persist()
        return removed
    }

    private fun load(): MutableList<StoredPlaylist> {
        val stored = runCatching {
            val json = prefs.getString(KEY_ITEMS, null)
            if (json.isNullOrBlank()) null else gson.fromJson<List<StoredPlaylist>>(json, type)
        }.getOrNull()
        return stored?.toMutableList() ?: mutableListOf()
    }

    private fun persist() {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    private companion object {
        const val PREFS = "aurora_shared_playlists"
        const val KEY_ITEMS = "items_json"
    }
}

/** 本机保存的分享歌单。[code] 保留着，方便「重新拉一次」或在 UI 上显示来源。 */
data class StoredPlaylist(
    val code: String,
    val title: String,
    val tracks: List<MusicTrack>,
    val importedAt: Long,
)
