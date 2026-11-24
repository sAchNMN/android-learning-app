package com.szc.learning.music.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.szc.learning.core.common.music.MusicTrack
import kotlinx.coroutines.flow.first

/**
 * 上次的播放现场：队列、播到第几首、播到哪一秒。
 *
 * 只为一个场景存在 —— **进程被系统杀掉后，蓝牙耳机/通知栏的播放键还能续上**。
 * 这个场景的系统回调（`onPlaybackResumption`）在新进程里触发，那时内存全空，
 * 只能从磁盘读。
 */
data class QueueSnapshot(
    @SerializedName("tracks") val tracks: List<MusicTrack>,
    @SerializedName("index") val index: Int,
    @SerializedName("positionMs") val positionMs: Long,
)

/**
 * 播放现场的落盘。
 *
 * 用 DataStore 而不是 SharedPreferences：写入是挂起函数、天然串行，
 * 不会出现「切歌瞬间和暂停同时写，后写的旧值覆盖新值」。
 * 存档只有一条，读出来是一次 JSON 解析，代价可忽略。
 *
 * 写时机很克制：只在切歌 / 暂停 / 销毁这类**离散事件**写，绝不跟着进度 tick 写盘 ——
 * 每 350ms 一次磁盘写入，既费电又会让主线程 I/O 抖动。
 */
class QueueSnapshotStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()

    suspend fun read(): QueueSnapshot? {
        val json = appContext.queueDataStore.data.first()[KEY] ?: return null
        return runCatching { gson.fromJson(json, QueueSnapshot::class.java) }.getOrNull()
    }

    suspend fun write(snapshot: QueueSnapshot) {
        val json = gson.toJson(snapshot)
        appContext.queueDataStore.edit { it[KEY] = json }
    }

    suspend fun clear() {
        appContext.queueDataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("snapshot_json")
    }
}

/** 文件名与 SessionStore / 播放缓存各自独立，互不影响。 */
private val Context.queueDataStore by preferencesDataStore(name = "queue_snapshot")
