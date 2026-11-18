package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack

/**
 * 推荐结果的本地缓存。
 *
 * 目的只有一个：**断网时推荐页不能是空白**。
 * 后端连不上就回放上一次拿到的那批歌，并在界面上标明「离线缓存」，
 * 而不是让用户对着骨架屏等一个永远不会来的响应。
 *
 * 只留最近一份，不做历史版本 —— 推荐页不是资料库，多存无益。
 */
class RecommendCacheStore(context: Context) {

    data class Cached(
        val tracks: List<MusicTrack>,
        val basedOn: List<String>,
        val reason: String,
        val savedAt: Long,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<MusicTrack>>() {}.type

    fun read(): Cached? {
        val json = prefs.getString(KEY_TRACKS, null) ?: return null
        val tracks = runCatching { gson.fromJson<List<MusicTrack>>(json, listType) }
            .getOrNull()
            ?.filter { it.id.isNotBlank() }
            ?: return null
        if (tracks.isEmpty()) return null
        val basedOn = prefs.getString(KEY_BASED_ON, null)
            ?.split('\u0001')
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        return Cached(
            tracks = tracks,
            basedOn = basedOn,
            reason = prefs.getString(KEY_REASON, null).orEmpty(),
            savedAt = prefs.getLong(KEY_SAVED_AT, 0L),
        )
    }

    fun write(tracks: List<MusicTrack>, basedOn: List<String>, reason: String) {
        if (tracks.isEmpty()) return
        prefs.edit()
            .putString(KEY_TRACKS, gson.toJson(tracks.map { it.copy(lyrics = null) }))
            .putString(KEY_BASED_ON, basedOn.joinToString("\u0001"))
            .putString(KEY_REASON, reason)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
    }

    private companion object {
        const val PREFS = "aurora_recommend_cache"
        const val KEY_TRACKS = "tracks_json"
        const val KEY_BASED_ON = "based_on"
        const val KEY_REASON = "reason"
        const val KEY_SAVED_AT = "saved_at"
    }
}
