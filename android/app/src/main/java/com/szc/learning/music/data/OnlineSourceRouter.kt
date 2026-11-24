package com.szc.learning.music.data

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.core.network.music.OnlinePlayback
import com.szc.learning.core.network.music.OnlineSearchResult
import com.szc.learning.music.data.local.KuwoDirectSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线音源的路由层：**后端起决定作用，后端不在时降级到客户端直连**。
 *
 * 为什么要单独一层，而不是在 ViewModel 里写 if：
 * 「先探测后端 → 失败降级 → 记录在线状态 → 通知 UI」这套逻辑会出现在搜索、点播、
 * 歌词、推荐四个地方，散在 ViewModel 里就是四份重复的状态机。
 *
 * 降级能力**不对称**，这是刻意的：
 * - 酷我：客户端直连原是后端宕机时的兜底，但 anti.s 接口现已失效（返回
 *   「请下载酷我 App」的提示音），因此全链路停用 —— 不搜、不播、不取歌词。
 * - 网易云 / 酷狗 / QQ：只有后端实现，后端挂了就明确告诉用户，不假装成「没搜到」
 *
 * 结果：后端离线时在线音源不可用，推荐/搜索退回本机曲库 + 收藏。这是刻意的诚实降级。
 *
 * 探测为什么单独一个 client 而不是复用 [MusicRepository] 的：
 * 那个 client 的 connect timeout 是 15 秒。后端没起时用户点一下搜索要等 15 秒才看到结果，
 * 这是不可接受的体验。探测用 2.5 秒超时，判死后一段时间内直接走本地，不再反复撞墙。
 */
class OnlineSourceRouter(
    private val remote: MusicRepository,
    private val kuwo: KuwoDirectSource,
    private val configStore: SourceConfigStore,
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {

    /**
     * 后端可达性。`null` = 还没探测过。
     *
     * 初始值是 null 而不是 true：乐观初始化的代价是首次搜索要等 15 秒超时。
     * 用 `AppContainer` 启动时的一次后台探测把这段等待挪到用户看不见的地方。
     */
    private val _backendOnline = MutableStateFlow<Boolean?>(null)
    val backendOnline: StateFlow<Boolean?> = _backendOnline.asStateFlow()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private var lastProbeAt = 0L
    private var watchJobStarted = false

    /**
     * 启动后台探测。只在 [AppContainer] 里调一次。
     *
     * 间隔分两档：在线时 45 秒探一次（省电，后端挂了最多 45 秒内被发现），
     * 离线时 15 秒探一次（用户在等，恢复要尽快感知）。
     */
    fun startWatching() {
        if (watchJobStarted) return
        watchJobStarted = true
        scope.launch {
            while (isActive) {
                probeNow()
                delay(if (_backendOnline.value == true) ONLINE_TTL_MS else OFFLINE_TTL_MS)
            }
        }
    }

    /** 立刻探一次，返回后端是否可达。 [force] 为 false 时复用 TTL 内的结果。 */
    suspend fun probeNow(force: Boolean = true): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force) {
            val ttl = if (_backendOnline.value == true) ONLINE_TTL_MS else OFFLINE_TTL_MS
            val known = _backendOnline.value
            if (known != null && now - lastProbeAt < ttl) return known
        }
        lastProbeAt = now
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(normalizedBase() + PROBE_PATH)
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        _backendOnline.value = ok
        return ok
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 聚合搜索。后端可用时由后端并行打其它平台；酷我全链路停用，不搜也不展示。
     *
     * 为什么后端结果也要过滤：后端 `/search` 仍可能返回酷我曲目，而酷我取址接口
     * 现在回的是「请下载酷我 App」的提示音。URL 格式合法，播放器只会在那里出声，
     * 用户听到的是广告 —— 所以必须在入库前把 sourceKey=kuwo 的整批剔掉。
     *
     * 离线时没有可用在线音源：其它三家只有后端实现，酷我已停用。明确返回空 +
     * 原因，让页面显示「在线音源不可用」，而不是假装搜到了什么。
     */
    suspend fun search(keyword: String, limit: Int = 20): OnlineSearchResult {
        if (probeNow(force = false)) {
            when (val result = remote.searchOnline(keyword, limit, cookies = configStore.cookiesJson())) {
                is AppResult.Success -> {
                    val usable = result.data.tracks.filterNot { it.sourceKey == KuwoDirectSource.KEY }
                    if (usable.isNotEmpty()) {
                        return result.data.copy(tracks = usable)
                    }
                }

                is AppResult.Failure -> _backendOnline.value = false
            }
        }
        return OnlineSearchResult(
            tracks = emptyList(),
            errors = mapOf(OFFLINE_HINT_KEY to OFFLINE_HINT_NO_SOURCE),
        )
    }

    // ---------------------------------------------------------------- 点播

    /**
     * 换取播放地址。
     *
     * 酷我**全链路停用**：anti.s 现在回的是「请下载酷我 App」的提示音，URL 格式
     * 合法、ExoPlayer 也能播，但用户听到的是广告。这类失败靠 `isPlayableUrl()`
     * 那种字符串校验挡不住，只能在入口直接判死，连请求都不发。
     *
     * 后端在线 → 后端换源（其余三个平台自动 fallback）；
     * 后端离线 → 其它平台明确提示需要后端，不再假装能播。
     */
    suspend fun play(track: MusicTrack): OnlinePlayback {
        if (track.sourceKey == KuwoDirectSource.KEY) {
            return OnlinePlayback(track = null, message = KUWO_DISABLED_MESSAGE)
        }
        if (probeNow(force = false)) {
            when (val result = remote.playOnline(track, cookies = configStore.cookiesJson())) {
                is AppResult.Success -> {
                    // 后端换源后 source 可能变成 kuwo，同样要拦住
                    val resolved = result.data.track
                    if (resolved != null && resolved.sourceKey != KuwoDirectSource.KEY) {
                        return result.data
                    }
                    if (resolved != null) {
                        return OnlinePlayback(track = null, message = KUWO_DISABLED_MESSAGE)
                    }
                }

                is AppResult.Failure -> _backendOnline.value = false
            }
        }
        return OnlinePlayback(
            track = null,
            message = if (_backendOnline.value == false) UNREACHABLE_MESSAGE else UNPLAYABLE_MESSAGE,
        )
    }

    // ---------------------------------------------------------------- 歌词

    /**
     * 歌词拿不到返回 null，调用方显示「暂无歌词」即可，不当错误处理。
     *
     * 整个链路包在 runCatching 里，因为**歌词是在播放页渲染时自动拉的**：
     * 这里漏一个异常出去，用户看到的就是「一进播放页就闪退」。
     * 曾经真踩过 —— 酷我接口返回 `{"data":null}`，Gson 的 getAsJsonObject
     * 直接抛 ClassCastException，整个进程被带走。歌词是最无所谓的功能，
     * 不该有能力弄崩页面。
     */
    suspend fun lyric(track: MusicTrack): String? {
        if (track.source != TrackSource.ONLINE) return null
        if (track.sourceKey == KuwoDirectSource.KEY) return null
        return runCatching {
            if (!probeNow(force = false)) return@runCatching null
            remote.onlineLyric(track, configStore.cookiesJson())
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 工具

    private fun normalizedBase(): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private companion object {
        const val PROBE_PATH = "app/music/sources"
        const val PROBE_TIMEOUT_MS = 2_500L
        const val ONLINE_TTL_MS = 45_000L
        const val OFFLINE_TTL_MS = 15_000L
        const val OFFLINE_HINT_KEY = "_offline"
        const val OFFLINE_HINT_NO_SOURCE = "后端未连接，在线音源不可用；可听本机歌曲"
        const val UNREACHABLE_MESSAGE = "在线音源需要连接后端，当前后端未连接"
        const val UNPLAYABLE_MESSAGE = "当前没有可用的在线音源"
        const val KUWO_DISABLED_MESSAGE = "酷我音源已停用"
    }
}
