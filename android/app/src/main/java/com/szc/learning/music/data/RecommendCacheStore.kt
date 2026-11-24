package com.szc.learning.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** 只用于把落盘挪出主线程；写入是「最后一次覆盖前一次」，不需要排队语义。 */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        // 序列化放到后台：20 首曲目拼 JSON 在这台机子上要 1~3ms，
        // 而调用点正好是「推荐结果到达」的那一刻 —— 用户这时通常已经把手搭上屏幕开始滑了，
        // 主线程多这 3ms 就是一帧顶到预算线上。
        writeScope.launch {
            // 在线曲目的 uri 是本次验证换到的临时 CDN 地址，落盘后下次启动就过期了。
            // 清掉让下次读取时重新走验证；本地/云端曲目的 uri 是有效路径，必须保留。
            val json = runCatching {
                gson.toJson(tracks.map {
                    if (it.source == TrackSource.ONLINE) {
                        it.copy(lyrics = null, uri = "")
                    } else {
                        it.copy(lyrics = null)
                    }
                })
            }
                .getOrNull()
                ?: return@launch
            prefs.edit()
                .putString(KEY_TRACKS, json)
                .putString(KEY_BASED_ON, basedOn.joinToString("\u0001"))
                .putString(KEY_REASON, reason)
                .putLong(KEY_SAVED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    private companion object {
        const val PREFS = "aurora_recommend_cache"
        const val KEY_TRACKS = "tracks_json"
        const val KEY_BASED_ON = "based_on"
        const val KEY_REASON = "reason"
        const val KEY_SAVED_AT = "saved_at"
    }
}
