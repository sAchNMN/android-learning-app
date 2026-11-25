package com.szc.learning.music.data

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.core.network.music.OnlinePlayback
import com.szc.learning.core.network.music.OnlineSearchResult
import com.szc.learning.core.network.music.UserPlaylistInfo
import com.szc.learning.music.data.local.KuwoDirectSource
import com.szc.learning.music.data.source.ClientSourceProvider
import com.szc.learning.music.data.source.SourceAccount
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 在线音源的统一入口。
 *
 * **主路径是客户端直连**：网易云 / 酷狗各有一份 [ClientSourceProvider]，
 * 按用户在音源管理页排的优先级并行搜索，合并去重后返回。
 * 后端只在三种情况下参与：
 *
 * 1. 客户端两个平台全都没结果 —— 可能是平台改版，后端还有适配空间；
 * 2. 客户端取址失败 —— 后端有换源策略，能再试别的平台；
 * 3. 分享码和评论 —— 这两件事物理上没法在单机上做。
 *
 * 所以「后端不可达」不再是致命状态：在线听歌照常，只是少了兜底。
 * [backendOnline] 保留给 UI 决定要不要提示「兜底不可用」。
 *
 * 酷我全链路停用（anti.s 返回「请下载酷我 App」提示音），入口直接判死，连请求都不发。
 */
class OnlineSourceRouter(
    private val remote: MusicRepository,
    providers: List<ClientSourceProvider>,
    private val configStore: SourceConfigStore,
    private val accountCache: SourceAccountCache,
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {

    /** 客户端直连实现，按 sourceKey 索引。 */
    private val providerByKey: Map<String, ClientSourceProvider> =
        providers.associateBy { it.key }

    /** 账号资料的单飞锁：两个页面同时要，只发一轮请求。 */
    private val accountMutex = Mutex()

    /**
     * 后端可达性。`null` = 还没探测过。
     *
     * 只影响兜底路径，客户端直连不需要它。启动时探一次，是为了让"兜底不可用"
     * 这条状态在用户点第一下之前就有结论。
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
     * 启动后台探测。只在 [com.szc.learning.AppContainer] 里调一次。
     *
     * 间隔分两档：在线时 45 秒探一次（省电），离线时 15 秒探一次（用户可能在等兜底）。
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
     * 聚合搜索：启用的客户端音源并行跑，合并去重后返回。
     *
     * 返回的候选数是 `limit` 的两倍，因为下游 [PlayableFilter] 会逐首真取一次地址、
     * 把播不了的剔掉。匿名态可播率本来就低（实测三平台 60 首里只有十来首能播），
     * 卡着 limit 返回会让用户搜完只剩五六条。多给一倍候选，验证完再截才够看。
     *
     * 全都没结果才回落后端。这不是客套 —— 平台改接口的当天，装着旧版 App 的用户
     * 仍然能靠后端拿到结果，代价只是慢一次。
     */
    suspend fun search(keyword: String, limit: Int = 20): OnlineSearchResult {
        val errors = LinkedHashMap<String, String>()

        // 1.1 客户端直连：按音源管理页的优先级发起，全部并行
        val active = orderedProviders()
        if (active.isNotEmpty()) {
            val tracks = parallelSearch(active, keyword, limit)
            if (tracks.isNotEmpty()) {
                return OnlineSearchResult(dedupe(tracks).take(limit * CANDIDATE_FACTOR), errors)
            }
            active.forEach { errors[it.key] = NO_RESULT_HINT }
        }

        // 1.2 客户端全空：后端再试一次
        if (probeNow(force = false)) {
            when (val result = remote.searchOnline(keyword, limit, cookies = configStore.cookiesJson())) {
                is AppResult.Success -> {
                    val usable = result.data.tracks.filterNot { it.sourceKey == KuwoDirectSource.KEY }
                    if (usable.isNotEmpty()) {
                        return result.data.copy(
                            tracks = dedupe(usable).take(limit * CANDIDATE_FACTOR),
                        )
                    }
                    errors.putAll(result.data.errors)
                }

                is AppResult.Failure -> _backendOnline.value = false
            }
        } else if (active.isEmpty()) {
            // 一个音源都没开、后端也连不上：这是真的没法搜，说清楚原因
            errors[OFFLINE_HINT_KEY] = OFFLINE_HINT_NO_SOURCE
        }

        return OnlineSearchResult(tracks = emptyList(), errors = errors)
    }

    /**
     * 并行搜所有启用的平台，按平台**轮转交错**合并。
     *
     * 不能直接把各平台结果首尾相接再截断：网易云一家就能返回满 [limit] 条，
     * 接在后面酷狗会被 `take(limit)` 整批切掉，用户看到的全是同一个平台，
     * 而且一首播不了就无处可换。轮转保证每个平台都有代表。
     *
     * 单个平台失败只丢它自己的结果，不影响其它平台。
     */
    private suspend fun parallelSearch(
        active: List<ClientSourceProvider>,
        keyword: String,
        limit: Int,
    ): List<MusicTrack> = coroutineScope {
        val groups = active.map { provider ->
            async {
                val cookie = configStore.cookieOf(provider.key)
                runCatching { provider.search(keyword, limit, cookie) }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val out = ArrayList<MusicTrack>(groups.sumOf { it.size })
        val longest = groups.maxOfOrNull { it.size } ?: 0
        for (index in 0 until longest) {
            for (group in groups) {
                if (index < group.size) out += group[index]
            }
        }
        out
    }

    // ---------------------------------------------------------------- 点播

    /**
     * 换取播放地址。
     *
     * 先按 [MusicTrack.sourceKey] 找客户端实现，拿到地址直接返回，不碰后端。
     * 客户端失败时后端再试一次 —— 会员曲目后端带了额外的换源参数，有时能拿到。
     *
     * 酷我直接判死：URL 格式合法但内容是提示音，字符串校验挡不住，
     * 只能在入口拦住，否则用户听到的是广告。
     */
    suspend fun play(track: MusicTrack): OnlinePlayback {
        if (track.sourceKey == KuwoDirectSource.KEY) {
            return OnlinePlayback(track = null, message = KUWO_DISABLED_MESSAGE)
        }

        // 1.1 客户端直连
        val provider = providerByKey[track.sourceKey]
        var clientFailure: OnlinePlayback? = null
        if (provider != null) {
            val cookie = configStore.cookieOf(provider.key)
            val result = runCatching { provider.resolve(track, cookie) }.getOrNull()
            if (result?.track != null) return result
            clientFailure = result
        }

        // 1.2 后端兜底：换成别的平台也算成功，换不到再回客户端给的原因
        val fallback = fallbackPlay(track)
        if (fallback?.track != null) return fallback

        return clientFailure ?: fallback ?: OnlinePlayback(
            track = null,
            message = if (_backendOnline.value == false) UNREACHABLE_MESSAGE else UNPLAYABLE_MESSAGE,
        )
    }

    private suspend fun fallbackPlay(track: MusicTrack): OnlinePlayback? {
        if (!probeNow(force = false)) return null
        return when (val result = remote.playOnline(track, cookies = configStore.cookiesJson())) {
            is AppResult.Success -> {
                val resolved = result.data.track
                // 后端换源后 source 可能变成酷我，同样要拦住
                if (resolved != null && resolved.sourceKey == KuwoDirectSource.KEY) {
                    OnlinePlayback(track = null, message = KUWO_DISABLED_MESSAGE)
                } else {
                    result.data
                }
            }

            is AppResult.Failure -> {
                _backendOnline.value = false
                null
            }
        }
    }

    // ---------------------------------------------------------------- 歌词

    /**
     * 歌词拿不到返回 null，调用方显示「暂无歌词」即可，不当错误处理。
     *
     * 整条链路包在 runCatching 里，因为**歌词是在播放页渲染时自动拉的**：
     * 这里漏一个异常出去，用户看到的就是「一进播放页就闪退」。
     * 曾经真踩过 —— 接口返回 `{"data":null}`，Gson 的 getAsJsonObject
     * 直接抛 ClassCastException，整个进程被带走。
     */
    suspend fun lyric(track: MusicTrack): String? {
        if (track.source != TrackSource.ONLINE) return null
        if (track.sourceKey == KuwoDirectSource.KEY) return null

        // 1.1 客户端直连
        providerByKey[track.sourceKey]?.let { provider ->
            val text = runCatching {
                provider.lyric(track, configStore.cookieOf(provider.key))
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }

        // 1.2 后端兜底
        return runCatching {
            if (!probeNow(force = false)) return@runCatching null
            remote.onlineLyric(track, configStore.cookiesJson())
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 推荐流

    /**
     * 平台自己的推荐流，给「猜你喜欢」在口味歌手不够时补齐。
     *
     * 并行问所有启用的平台，取第一个有结果的 —— 目前只有网易云实现了
     * 匿名可用的新歌推荐，其它平台返回空（不发请求）。
     */
    suspend fun dailyNewSongs(limit: Int = 20): List<MusicTrack> = coroutineScope {
        orderedProviders()
            .map { provider ->
                async {
                    val cookie = configStore.cookieOf(provider.key)
                    runCatching { provider.dailyRecommend(limit, cookie) }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    // ---------------------------------------------------------------- 账号歌单

    /**
     * 账号里的歌单。只有填了 Cookie 的平台才有内容。
     *
     * 这里不再回落后端：账号歌单和后端本来就是同一条请求（后端也只是代理平台），
     * 客户端直连反而少一次中转，Cookie 也不用交出去。
     */
    suspend fun myPlaylists(): List<UserPlaylistInfo> = coroutineScope {
        orderedProviders()
            .map { provider ->
                async {
                    val cookie = configStore.cookieOf(provider.key)
                    if (cookie.isBlank()) return@async emptyList()
                    runCatching { provider.myPlaylists(cookie) }.getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
    }

    /** 拉某个歌单的曲目。失败返回 [AppResult.Failure]，UI 才能区分"空歌单"和"没拉到"。 */
    suspend fun playlistTracks(
        source: String,
        playlistId: String,
        name: String?,
        limit: Int = 200,
    ): AppResult<List<MusicTrack>> {
        val provider = providerByKey[source]
            ?: return AppResult.Failure("当前版本不支持这个音源的歌单")
        if (playlistId.isBlank()) return AppResult.Failure("歌单 id 为空")
        val cookie = configStore.cookieOf(source)
        return runCatching {
            provider.playlistTracks(playlistId, name, limit, cookie)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { AppResult.Failure(it.message ?: "歌单加载失败") },
        )
    }

    // ---------------------------------------------------------------- 账号资料

    /**
     * 登录后的账号资料。
     *
     * 三层保护，顺序不能换：
     * 1. **没有 Cookie 直接返回 null** —— 未登录不该产生任何请求；
     * 2. **TTL 内读缓存** —— 进出页面、切 tab 都不打平台；
     * 3. **Mutex 内二次检查** —— 两个页面同时要数据时只发一轮请求（单飞）。
     *
     * 请求失败时退回过期缓存：显示旧数据比显示空白有用，用户至少知道
     * 自己登录的是哪个账号。真的连旧缓存都没有才返回 null。
     */
    suspend fun account(key: String, force: Boolean = false): SourceAccount? {
        val provider = providerByKey[key] ?: return null
        val cookie = configStore.cookieOf(key)
        if (cookie.isBlank()) return null
        if (!force) accountCache.get(key)?.let { return it }

        return accountMutex.withLock {
            // 4.1 等锁期间别的调用可能已经填好缓存，再查一次
            if (!force) accountCache.get(key)?.let { return@withLock it }
            // 4.2 真正的请求：provider 内部已按 3 次接口 + 节流执行
            val fetched = runCatching { provider.account(cookie) }.getOrNull()
            if (fetched != null) {
                accountCache.put(fetched)
                fetched
            } else {
                // 4.3 拿不到就退过期缓存，别让页面变空白
                accountCache.getStale(key)
            }
        }
    }

    /** 退出登录时连同缓存一起清，下次登录不会读到上一个人的资料。 */
    fun clearAccount(key: String) {
        accountCache.clear(key)
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 启用的客户端音源，顺序即优先级。
     *
     * [SourceConfigStore.enabledKeys] 已按 priority 升序；酷我在 store 层就被强制关闭，
     * 这里再滤一次是防止配置被外部改写后出现"能播广告"的路径。
     */
    private fun orderedProviders(): List<ClientSourceProvider> =
        configStore.enabledKeys()
            .filter { it != KuwoDirectSource.KEY }
            .mapNotNull { providerByKey[it] }

    /**
     * 跨平台去重交给 [TrackDedupe]：规则必须与后端 `RecommendService.dedupeKey` 一致，
     * 不一致会导致后端兜底时同一首歌又冒出来一次，列表里出现两行一样的歌。
     */
    private fun dedupe(tracks: List<MusicTrack>): List<MusicTrack> = TrackDedupe.distinct(tracks)

    private fun normalizedBase(): String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private companion object {
        const val PROBE_PATH = "app/music/sources"
        const val PROBE_TIMEOUT_MS = 2_500L
        const val ONLINE_TTL_MS = 45_000L
        const val OFFLINE_TTL_MS = 15_000L
        const val OFFLINE_HINT_KEY = "_offline"
        const val OFFLINE_HINT_NO_SOURCE = "没有启用任何在线音源；可听本机歌曲"
        const val NO_RESULT_HINT = "无结果"
        const val UNREACHABLE_MESSAGE = "当前离线，换不到播放地址"
        const val UNPLAYABLE_MESSAGE = "当前没有可用的音源"
        const val KUWO_DISABLED_MESSAGE = "酷我音源已停用"

        /**
         * 候选倍数：搜索返回 `limit × 2` 首，留给可播性验证去剔。
         * 匿名态实测只有两成左右能真取到地址，卡着 limit 返回会让列表短得没法用。
         */
        const val CANDIDATE_FACTOR = 2
    }
}
