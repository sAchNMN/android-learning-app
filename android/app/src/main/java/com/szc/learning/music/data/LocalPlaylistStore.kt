package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import java.util.UUID

/**
 * 自建歌单，存本机。
 *
 * 和 [SharedPlaylistStore] 分开的原因：那是「导入别人分享码」的结果，
 * 语义是只读快照（重新导入会整体覆盖）；这里是用户自己攒的、可以随时增删改的列表。
 * 塞进同一张表会让「重新导入」误删用户手动加的曲目。
 *
 * 用 SharedPreferences + Gson，与收藏、音源配置同一套做法：数据是几十 KB 的 JSON，
 * 上 DataStore 或 Room 都要改一批调用点，收益为零。
 *
 * 曲目整份存而不是只存 key：在线曲目重装后本地没有任何库能还原标题和封面，
 * 只存 key 等于歌单里一排空白行。这一点和 [FavoritesStore] 的取舍一致。
 */
class LocalPlaylistStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<LocalPlaylist>>() {}.type

    private val items: MutableList<LocalPlaylist> by lazy { load() }

    /** 最近改过的在前。 */
    @Synchronized
    fun all(): List<LocalPlaylist> = items.sortedByDescending { it.updatedAt }

    @Synchronized
    fun find(id: String): LocalPlaylist? = items.firstOrNull { it.id == id }

    @Synchronized
    fun create(title: String): LocalPlaylist {
        val now = System.currentTimeMillis()
        val playlist = LocalPlaylist(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { DEFAULT_TITLE },
            tracks = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        items += playlist
        persist()
        return playlist
    }

    @Synchronized
    fun rename(id: String, title: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val name = title.trim()
        if (name.isBlank()) return false
        items[index] = items[index].copy(title = name, updatedAt = System.currentTimeMillis())
        persist()
        return true
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val removed = items.removeAll { it.id == id }
        if (removed) persist()
        return removed
    }

    /** 加一首。已经在里面就返回 false，UI 据此提示「已存在」而不是假装成功。 */
    @Synchronized
    fun addTrack(id: String, track: MusicTrack): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val playlist = items[index]
        if (playlist.tracks.any { it.key == track.key }) return false
        items[index] = playlist.copy(
            tracks = playlist.tracks + track,
            updatedAt = System.currentTimeMillis(),
        )
        persist()
        return true
    }

    @Synchronized
    fun removeTrack(id: String, trackKey: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        val playlist = items[index]
        val remaining = playlist.tracks.filterNot { it.key == trackKey }
        if (remaining.size == playlist.tracks.size) return false
        items[index] = playlist.copy(tracks = remaining, updatedAt = System.currentTimeMillis())
        persist()
        return true
    }

    @Synchronized
    fun containsTrack(id: String, trackKey: String): Boolean =
        items.firstOrNull { it.id == id }?.tracks?.any { it.key == trackKey } == true

    private fun load(): MutableList<LocalPlaylist> {
        val stored = runCatching {
            val json = prefs.getString(KEY_ITEMS, null)
            if (json.isNullOrBlank()) null else gson.fromJson<List<LocalPlaylist>>(json, type)
        }.getOrNull()
        return stored?.toMutableList() ?: mutableListOf()
    }

    private fun persist() {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    private companion object {
        const val PREFS = "aurora_local_playlists"
        const val KEY_ITEMS = "items_json"
        const val DEFAULT_TITLE = "未命名歌单"
    }
}

/** 用户自建的歌单。[tracks] 是完整曲目，不是 key —— 理由见类注释。 */
data class LocalPlaylist(
    val id: String,
    val title: String,
    val tracks: List<MusicTrack>,
    val createdAt: Long,
    val updatedAt: Long,
)
