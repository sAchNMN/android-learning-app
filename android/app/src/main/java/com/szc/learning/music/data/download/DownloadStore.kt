package com.szc.learning.music.data.download

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import java.io.File

/**
 * 离线文件的索引。
 *
 * 只做两件事：记「哪首歌对应哪个文件」、给出文件路径。字节流不归它管。
 *
 * 为什么不用 SimpleCache 续命：[FeedAudioCache] 的缓存键是完整 URL，而在线音源的
 * 播放地址带 auth token，每次取址都会变 —— 换个会话 URL 不同，缓存就永远命中不了。
 * 所以离线必须自己按 `source + id` 落盘，与 URL 解耦。
 *
 * 目录用 `getExternalFilesDir` 而不是 `filesDir`：一首无损三四十兆，
 * 几十首就能把内部存储吃掉；外部私有目录卸载时同样会被清掉，不存在残留。
 *
 * 文件扩展名统一用 `.audio`：真实容器是 mp3 还是 flac 由取址结果决定，
 * 猜一个 `.mp3` 会让 flac 文件被别的播放器误判；ExoPlayer 靠内容嗅探，不看扩展名。
 */
class DownloadStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val type = object : TypeToken<List<OfflineTrack>>() {}.type

    private val items: MutableList<OfflineTrack> by lazy { load() }

    /** 离线文件目录。所有写盘/删除都走这里，别在别处拼路径。 */
    val dir: File by lazy {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(base, DIR_NAME).apply { mkdirs() }
    }

    /** 最近保存的排前面。 */
    @Synchronized
    fun all(): List<OfflineTrack> = items.sortedByDescending { it.savedAt }

    @Synchronized
    fun find(key: String): OfflineTrack? = items.firstOrNull { it.key == key }

    /**
     * 可播放的本地文件。索引里有但文件被系统清掉时返回 null，
     * 调用方会退回在线取址 —— 索引和磁盘不同步是常态，不该让播放失败。
     */
    @Synchronized
    fun playableFile(key: String): File? {
        val entry = find(key) ?: return null
        val file = File(dir, entry.fileName)
        return if (file.isFile && file.length() > 0) file else null
    }

    @Synchronized
    fun totalBytes(): Long = items.sumOf { it.sizeBytes }

    @Synchronized
    fun has(key: String): Boolean = items.any { it.key == key }

    /** 目标文件路径。同名文件先删后写，避免续传写出半个文件。 */
    fun fileFor(sourceKey: String, id: String): File = File(dir, "${sourceKey}_${sanitize(id)}.audio")

    @Synchronized
    fun put(entry: OfflineTrack) {
        items.removeAll { it.key == entry.key }
        items += entry
        persist()
    }

    /** 删索引 + 删文件。返回是否有东西真被删掉。 */
    @Synchronized
    fun remove(key: String): Boolean {
        val entry = items.firstOrNull { it.key == key } ?: return false
        File(dir, entry.fileName).delete()
        items.remove(entry)
        persist()
        return true
    }

    /** 索引里有、磁盘上没有的条目（系统清缓存、用户手动删文件）。 */
    @Synchronized
    fun prune(): Int {
        val gone = items.filter { !File(dir, it.fileName).isFile }
        if (gone.isEmpty()) return 0
        items.removeAll(gone)
        persist()
        return gone.size
    }

    /** 还原成可播放的曲目：只用于「离线列表」直接播放，字段够播放器展示即可。 */
    fun toTrack(entry: OfflineTrack): MusicTrack? {
        val file = playableFile(entry.key) ?: return null
        return MusicTrack(
            id = entry.id,
            title = entry.title,
            artist = entry.artist,
            album = entry.album,
            durationMs = entry.durationMs,
            uri = file.toURI().toString(),
            artUri = entry.artUri.ifBlank { null },
            source = TrackSource.ONLINE,
            sourceKey = entry.sourceKey,
            hash = entry.hash,
            albumId = entry.albumId,
            albumAudioId = entry.albumAudioId,
            playable = true,
        )
    }

    private fun load(): MutableList<OfflineTrack> {
        val stored = runCatching {
            val json = prefs.getString(KEY_ITEMS, null)
            if (json.isNullOrBlank()) null else gson.fromJson<List<OfflineTrack>>(json, type)
        }.getOrNull()
        return stored?.toMutableList() ?: mutableListOf()
    }

    private fun persist() {
        prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
    }

    /**
     * id 直接进文件名前先滤一遍。酷狗是纯 hex、网易云是数字，理论上都安全，
     * 但 id 来自平台响应，写进路径前不能假设它守规矩。
     */
    private fun sanitize(id: String): String =
        id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifBlank { "unknown" }

    private companion object {
        const val PREFS = "aurora_offline"
        const val KEY_ITEMS = "items_json"
        const val DIR_NAME = "offline"
    }
}

/**
 * 一条离线记录。
 *
 * 存 `hash / albumId / albumAudioId` 是为了「文件被删了但索引还在」时还能重新取址 ——
 * 酷狗取址必须带这几个参数，只留 id 恢复不回来。
 */
data class OfflineTrack(
    val key: String,
    val sourceKey: String,
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artUri: String,
    val hash: String,
    val albumId: String,
    val albumAudioId: String,
    val fileName: String,
    val sizeBytes: Long,
    val savedAt: Long,
)
