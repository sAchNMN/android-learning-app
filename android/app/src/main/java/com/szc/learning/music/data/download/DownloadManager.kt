package com.szc.learning.music.data.download

import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.SharedHttp
import com.szc.learning.core.network.music.OnlinePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 离线下载。
 *
 * 顺序下载而不是并发：一首 320k 的歌 5~10MB，并发五六条会把带宽全抢走，
 * 正在播的那首立刻开始卡。队列在这里，用户点一堆也只是排队。
 *
 * 取址走外部传进来的 [resolve]（实际是 `OnlineSourceRouter::play`），
 * 这样下载和在线播放用**同一套**音源优先级、Cookie、音质设置，不会出现
 * 「播的时候有会员音质、下载下来变成 128k」这种两套逻辑打架的情况。
 *
 * 地址过期是常态：在线音源的播放地址通常几小时就失效，所以必须在拿到地址的
 * 那一刻立刻开下，不能排到「明天再下」—— 队列里的任务都是拿到地址后马上写盘。
 */
class DownloadManager(
    private val store: DownloadStore,
    private val resolve: suspend (MusicTrack) -> OnlinePlayback,
    private val scope: CoroutineScope,
) {

    private val client = SharedHttp.base.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** 任务状态。UI 只读它，不关心队列内部顺序。 */
    private val _tasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTask>> = _tasks.asStateFlow()

    /**
     * 无界队列：用户点「全部下载」时可能一次进来几十首，
     * 限容会让后面的被静默丢掉，而用户以为都下上了。
     */
    private val queue = Channel<MusicTrack>(Channel.UNLIMITED)

    @Volatile
    private var cancelled = false

    init {
        scope.launch(Dispatchers.IO) {
            for (track in queue) {
                runOne(track)
                if (cancelled) break
            }
        }
    }

    /** 入队。已经下过、正在排队的直接跳过，避免重复写同一份文件。 */
    fun enqueue(tracks: List<MusicTrack>) {
        cancelled = false
        val fresh = tracks.filter { track ->
            track.uri.isNotBlank() || track.sourceKey.isNotBlank()
        }.filter { store.playableFile(it.key) == null }

        if (fresh.isEmpty()) return
        val snapshot = _tasks.value.toMutableMap()
        fresh.forEach { track ->
            if (snapshot[track.key]?.state == DownloadState.QUEUED ||
                snapshot[track.key]?.state == DownloadState.RUNNING
            ) {
                return@forEach
            }
            snapshot[track.key] = DownloadTask(
                key = track.key,
                title = track.title,
                artist = track.artist,
                state = DownloadState.QUEUED,
            )
        }
        _tasks.value = snapshot
        fresh.forEach { queue.trySend(it) }
    }

    /** 停止后续任务。已经在写的那首会写完当前块再退出，不留半个文件。 */
    fun cancelAll() {
        cancelled = true
        val snapshot = _tasks.value.mapValues { (_, task) ->
            if (task.state == DownloadState.QUEUED) task.copy(state = DownloadState.CANCELLED) else task
        }
        _tasks.value = snapshot
    }

    /** 清掉已完成/已失败的状态，任务列表恢复干净。 */
    fun clearFinished() {
        _tasks.value = _tasks.value.filterValues {
            it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED
        }
    }

    fun isDownloading(key: String): Boolean {
        val state = _tasks.value[key]?.state
        return state == DownloadState.QUEUED || state == DownloadState.RUNNING
    }

    private suspend fun runOne(track: MusicTrack) {
        if (cancelled) {
            update(track.key) { it.copy(state = DownloadState.CANCELLED) }
            return
        }
        // 已经下过就直接标记完成，重复入队不会重写文件
        if (store.playableFile(track.key) != null) {
            update(track.key) { it.copy(state = DownloadState.DONE, progress = 100) }
            return
        }
        update(track.key) { it.copy(state = DownloadState.RUNNING, progress = 0) }

        val target = store.fileFor(track.sourceKey, track.id)
        val temp = File(target.parentFile, target.name + ".part")
        try {
            // 1.1 换播放地址（与在线播放同一条路径，音质设置一并生效）
            val playback = resolve(track)
            val resolved = playback.track
                ?: throw IOException(playback.message ?: "取不到播放地址")

            // 1.2 流式写临时文件，失败不留半个文件在正式路径上
            downloadTo(resolved.uri, temp, refererOf(track.sourceKey)) { percent ->
                update(track.key) { it.copy(progress = percent) }
            }

            // 1.3 落正式路径
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) throw IOException("保存文件失败")

            store.put(
                OfflineTrack(
                    key = track.key,
                    sourceKey = track.sourceKey,
                    id = track.id,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    artUri = track.artUri.orEmpty(),
                    hash = track.hash,
                    albumId = track.albumId,
                    albumAudioId = track.albumAudioId,
                    fileName = target.name,
                    sizeBytes = target.length(),
                    savedAt = System.currentTimeMillis(),
                ),
            )
            update(track.key) { it.copy(state = DownloadState.DONE, progress = 100) }
        } catch (ex: Exception) {
            temp.delete()
            update(track.key) {
                it.copy(
                    state = DownloadState.FAILED,
                    message = ex.message ?: "下载失败",
                )
            }
        }
    }

    private fun downloadTo(url: String, target: File, referer: String, onProgress: (Int) -> Unit) {
        val request = Request.Builder()
            .url(url)
            // CDN 对空 Referer 的音频请求有防盗链，带上平台首页比带歌曲页更稳
            .header("Referer", referer)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败 HTTP ${response.code}")
            val body = response.body ?: throw IOException("下载失败：响应为空")
            val total = body.contentLength()
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L

            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        if (cancelled) throw IOException("已取消")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) {
                            onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                    output.flush()
                }
            }
            if (written <= 0) throw IOException("下载失败：文件为空")
        }
    }

    private fun update(key: String, transform: (DownloadTask) -> DownloadTask) {
        val current = _tasks.value[key] ?: return
        _tasks.value = _tasks.value + (key to transform(current))
    }

    /** 只有两个平台在用，映射写死比抽象一层接口实在。 */
    private fun refererOf(sourceKey: String): String = when (sourceKey) {
        "kugou" -> "https://www.kugou.com/"
        else -> "https://music.163.com/"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 30_000L
        const val BUFFER_BYTES = 32 * 1024
    }
}

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class DownloadTask(
    val key: String,
    val title: String,
    val artist: String,
    val state: DownloadState,
    /** 0~100；-1 表示服务端没给 Content-Length（chunked），只能显示不确定进度。 */
    val progress: Int = 0,
    val message: String? = null,
)
