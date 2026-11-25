package com.szc.learning.music.player

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.szc.learning.core.common.music.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 刷歌流的音频缓存。
 *
 * 刷歌的特点是「下一首随时可能被划出来」，所以不能等用户点播放才开始下载：
 * 播放中的这首边播边写盘，后面两首提前预取，滑过去时基本是本地读取。
 *
 * 生命周期只有一个约定：**不跨会话**。
 * 目录在构造时整个删掉再重建，也就是上一次进程留下的音频不会带进这一次；
 * 进程被杀时缓存文件随 cacheDir 一起被系统回收，一次会话的占用上限还由
 * [MAX_BYTES] 兜底，不会把手机塞满。
 *
 * SimpleCache 要求同一目录在进程内**只有一个实例**，所以它挂在 AppContainer 上，
 * 由 PlaybackService 取用，不要在这里以外的地方 new。
 */
class FeedAudioCache(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val cacheDir = File(appContext.cacheDir, DIR_NAME)
    private val databaseProvider = StandaloneDatabaseProvider(appContext)

    private val cache: SimpleCache

    init {
        // 上个进程的残留：改名是瞬时操作，先把旧目录挪开，新建的缓存照常从空开始；
        // 真正的删除放到后台 IO。deleteRecursively 放主线程会拖慢首帧，
        // 而且只删文件不删库会留下 ExoPlayer 索引表，启动次数越多表越大。
        val stale = File(appContext.cacheDir, "$STALE_PREFIX${System.currentTimeMillis()}")
        runCatching { if (cacheDir.exists()) cacheDir.renameTo(stale) }
        cacheDir.mkdirs()
        cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            databaseProvider,
        )
        scope.launch(Dispatchers.IO) { sweepStale() }
    }

    /** 后台清掉所有改名后的旧目录（含上次没删完的），连 ExoPlayer 索引一起。 */
    private fun sweepStale() {
        val keep = cacheDir.absolutePath
        appContext.cacheDir.listFiles()?.forEach { old ->
            if (old.absolutePath == keep || !old.name.startsWith(STALE_PREFIX)) return@forEach
            runCatching { SimpleCache.delete(old, databaseProvider) }
                .onFailure { runCatching { old.deleteRecursively() } }
        }
    }

    /**
     * 上游按 URI scheme 分发：http(s) 走带缓存的 HTTP 源，content:// 和 file:// 交给
     * [DefaultDataSource] 自带的本地源。
     *
     * 以前上游直接挂 DefaultHttpDataSource，本机曲目（content://）会被当成 URL 打开，
     * 报 `unknown protocol: content`。酷我停用后推荐页换成本机曲库，这个隐疾才暴露出来。
     */
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(USER_AGENT)
        .setConnectTimeoutMs(TIMEOUT_MS)
        .setReadTimeoutMs(TIMEOUT_MS)
        .setAllowCrossProtocolRedirects(true)

    private val cachedHttpFactory: CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(httpFactory)
        // 缓存读坏了就退回网络，宁可多耗流量也不要卡住播放
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val routingFactory = DefaultDataSource.Factory(appContext, cachedHttpFactory)

    /** 交给 ExoPlayer：http(s) 先查缓存，本机文件直接读。 */
    fun mediaSourceFactory(): DefaultMediaSourceFactory = DefaultMediaSourceFactory(routingFactory)

    /**
     * 把整首拉到缓存。
     *
     * 已经缓存完整时 CacheWriter 会立刻返回，重复调用很便宜；
     * 所以调用方不需要自己记「这首预取过没有」。
     */
    suspend fun prefetch(track: MusicTrack): Boolean = withContext(Dispatchers.IO) {
        if (track.uri.isBlank()) return@withContext false
        // 本机曲目本来就在盘上，不需要也不应该塞进 HTTP 缓存（content:// 会被 URL 解析器拒绝）
        if (!track.uri.startsWith("http")) return@withContext false
        runCatching {
            // key 就是 uri 本身（media3 默认的 CacheKeyFactory），和播放时用的是同一个
            val spec = DataSpec.Builder().setUri(track.uri).build()
            val writer = CacheWriter(
                cachedHttpFactory.createDataSource(),
                spec,
                /* cacheKeyFactory = */ null,
                /* progressListener = */ null,
            )
            writer.cache()
        }.isSuccess
    }

    /**
     * 清空本会话缓存。
     *
     * 只在播放器已经 release 之后调用：SimpleCache 不允许边写边删。
     * 这里按 key 逐条删而不是删目录，索引和文件能保持一致，之后还能继续用。
     */
    fun clear() {
        runCatching {
            cache.keys.toList().forEach { key -> cache.removeResource(key) }
        }
    }

    private companion object {
        const val DIR_NAME = "feed_audio"

        /** 改名后的旧目录前缀，后台清理时按它识别。 */
        const val STALE_PREFIX = "feed_audio-old-"

        /** 一次会话的内存上限：刷一小时也到不了，超了按 LRU 淘汰。 */
        const val MAX_BYTES = 256L * 1024 * 1024

        const val TIMEOUT_MS = 12_000

        const val USER_AGENT = "Aurora/1.0 (Android)"
    }
}
