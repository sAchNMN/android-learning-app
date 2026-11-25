package com.szc.learning.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.MusicSourceInfo
import com.szc.learning.core.network.music.OnlinePlayback
import com.szc.learning.core.network.music.ShareResult
import com.szc.learning.core.network.music.SharedPlaylist
import com.szc.learning.core.network.music.UserPlaylistInfo
import com.szc.learning.music.data.PlayableFilter
import com.szc.learning.music.data.StoredPlaylist
import com.szc.learning.music.player.PlayMode
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.account.AccountUiState
import com.szc.learning.music.ui.common.sourceLabel
import com.szc.learning.music.ui.rank.RankType
import com.szc.learning.music.ui.rank.RankUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 曲库、收藏与播放的共享 ViewModel（Activity 作用域）。
 *
 * 五个 tab 都要读同一份曲库，所以不能各建一个：否则切 tab 就重新扫一遍 MediaStore，
 * 而且"最近播放/收藏"在每个 tab 里各自一套。这里统一持有。
 *
 * 注意：它不持有任何 Activity/Fragment 引用，只暴露 StateFlow。
 */
class MusicViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as LearningApp).container
    private val player = container.playerController

    /** 收藏的写入口与 Service 共用同一条路径（通知栏也能点红心）。 */
    private val favoritesRepo = container.favoritesRepository

    private val _local = MutableStateFlow<List<MusicTrack>>(emptyList())
    val local: StateFlow<List<MusicTrack>> = _local.asStateFlow()

    private val _recent = MutableStateFlow<List<MusicTrack>>(emptyList())
    val recent: StateFlow<List<MusicTrack>> = _recent.asStateFlow()

    private val _favorites = MutableStateFlow(container.favoritesStore.keys())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** 收藏的完整曲目：在线曲目也在里面，不再依赖「曲库里有没有这首歌」。 */
    private val _favoriteTracks = MutableStateFlow(container.favoritesStore.tracks())
    val favoriteList: StateFlow<List<MusicTrack>> = _favoriteTracks.asStateFlow()

    // ---------------------------------------------------------------- 推荐

    private val _recommend = MutableStateFlow<List<MusicTrack>>(emptyList())
    val recommend: StateFlow<List<MusicTrack>> = _recommend.asStateFlow()

    /** 口味种子（歌手名），用于「根据 周杰伦、陈奕迅 推荐」。 */
    private val _recommendBasedOn = MutableStateFlow<List<String>>(emptyList())
    val recommendBasedOn: StateFlow<List<String>> = _recommendBasedOn.asStateFlow()

    /** favorites / likes / discover / cache，页面据此解释推荐来源。 */
    private val _recommendReason = MutableStateFlow("")
    val recommendReason: StateFlow<String> = _recommendReason.asStateFlow()

    private val _recommendLoading = MutableStateFlow(false)
    val recommendLoading: StateFlow<Boolean> = _recommendLoading.asStateFlow()

    /** 本次是否用的本地缓存（后端不可达）。 */
    private val _recommendOffline = MutableStateFlow(false)
    val recommendOffline: StateFlow<Boolean> = _recommendOffline.asStateFlow()

    private val _recommendError = MutableStateFlow<String?>(null)
    val recommendError: StateFlow<String?> = _recommendError.asStateFlow()

    private var lastRecommendAt = 0L

    // ---------------------------------------------------------------- 榜单

    private val _rank = MutableStateFlow(RankUiState())
    val rank: StateFlow<RankUiState> = _rank.asStateFlow()

    private var lastRankAt = 0L

    /**
     * 拉一次榜单。
     *
     * 没有离线降级：榜单是跨用户聚合，本机算出来的东西不是榜单 ——
     * 显示一份"自己的播放次数排行"比显示"连不上"更误导。
     * 失败时把原因原样带到 [RankUiState.message]，页面照实说。
     *
     * @param refresh 用户点了刷新 / 刚播完歌回到榜单，绕过 30 秒节流
     */
    fun loadRank(type: String = RankType.HOT, refresh: Boolean = false) {
        if (_rank.value.loading) return
        val current = _rank.value
        if (!refresh && current.type == type && current.tracks.isNotEmpty() &&
            System.currentTimeMillis() - lastRankAt < RANK_TTL_MS
        ) {
            return
        }
        _rank.value = current.copy(type = type, loading = true, message = null)
        viewModelScope.launch {
            when (val result = container.musicRepository.rank(type)) {
                is AppResult.Success -> {
                    val data = result.data
                    _rank.value = RankUiState(
                        type = type,
                        loading = false,
                        tracks = data.tracks,
                        eventCount = data.eventCount,
                        windowStart = data.windowStart,
                        updatedAt = data.updatedAt,
                        message = data.message,
                    )
                    lastRankAt = System.currentTimeMillis()
                }

                is AppResult.Failure -> _rank.value = current.copy(
                    type = type,
                    loading = false,
                    tracks = emptyList(),
                    eventCount = 0,
                    message = result.message,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 同步

    private val _syncedPlaylists = MutableStateFlow<List<UserPlaylistInfo>>(emptyList())
    val syncedPlaylists: StateFlow<List<UserPlaylistInfo>> = _syncedPlaylists.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /**
     * 同步回执。
     *
     * 只有**用户主动点同步**才会发消息：自动同步（进前台、进推荐页）本来就是为了
     * 让数据保持最新，每次弹一句「已同步 0 首喜欢」纯属打扰。
     * 用 SharedFlow 而不是 StateFlow —— 一次性事件不该在重新订阅时被重放一遍。
     */
    private val _syncNotice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val syncNotice: SharedFlow<String> = _syncNotice.asSharedFlow()

    private var lastLibrarySyncAt = 0L

    /** 收藏同步的一次性提示（已同步到哪个平台 / 为什么没同步上）。 */
    private val _likeNotice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val likeNotice: SharedFlow<String> = _likeNotice.asSharedFlow()

    private val _listenMinutes = MutableStateFlow(container.listenStatsStore.minutes())
    val listenMinutes: StateFlow<Long> = _listenMinutes.asStateFlow()

    /** 当前播放队列。正在播放页的"队列"弹层直接读它。 */
    val queue: StateFlow<List<MusicTrack>> = player.queue

    private val _localLoading = MutableStateFlow(false)
    val localLoading: StateFlow<Boolean> = _localLoading.asStateFlow()

    // ---------------------------------------------------------------- 在线音源

    private val _online = MutableStateFlow<List<MusicTrack>>(emptyList())
    val online: StateFlow<List<MusicTrack>> = _online.asStateFlow()

    private val _onlineLoading = MutableStateFlow(false)
    val onlineLoading: StateFlow<Boolean> = _onlineLoading.asStateFlow()

    /** 整次在线搜索失败（后端不可达 / 没开音源）；单平台失败在 [onlineErrors]。 */
    private val _onlineError = MutableStateFlow<String?>(null)
    val onlineError: StateFlow<String?> = _onlineError.asStateFlow()

    private val _onlineErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val onlineErrors: StateFlow<Map<String, String>> = _onlineErrors.asStateFlow()

    private var onlineJob: Job? = null

    private val _sources = MutableStateFlow<List<MusicSourceInfo>>(emptyList())
    val sources: StateFlow<List<MusicSourceInfo>> = _sources.asStateFlow()

    private val _sourcesLoading = MutableStateFlow(false)
    val sourcesLoading: StateFlow<Boolean> = _sourcesLoading.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    // ---------------------------------------------------------------- 账号资料

    /**
     * 账号页状态。声明在所有依赖之后 —— 顶部 `init` 块里读后面声明的
     * StateFlow 会拿到 null（属性按源码顺序初始化），启动即崩。
     */
    private val _accountState = MutableStateFlow(AccountUiState())
    val accountState: StateFlow<AccountUiState> = _accountState.asStateFlow()

    /** 播放器状态直接转出去，页面不需要知道 PlayerController 的存在。 */
    val playerState: StateFlow<PlayerUiState> = player.state

    /**
     * 播放进度单独一条流。
     *
     * 只有进度条、时间文字、歌词高亮该订阅它；其余 UI 订阅 [playerState] 就够了，
     * 否则每 350ms 整页重跑一遍 render。
     */
    val position: StateFlow<Long> = player.position

    /**
     * 读取当前曲目的歌词。
     *
     * 在线曲目先走客户端酷我直连（后端不在时也有歌词），拿不到再问后端；
     * 本地/云端仍走 ID3 解析。
     *
     * 整体包 runCatching：**歌词是在播放页渲染时自动拉的**，这里漏一个异常出去
     * 就是「一进播放页就闪退」。已经因为酷我返回 `data:null` 崩过一次，
     * 歌词这么边缘的功能不该有能力弄崩页面。
     */
    suspend fun lyrics(track: MusicTrack): String? = runCatching {
        if (track.source == TrackSource.ONLINE) {
            container.onlineSourceRouter.lyric(track)
        } else {
            container.lyricsRepository.lyrics(track)
        }
    }.getOrNull()

    // 累计听歌时长的游标。放在 ViewModel 里是因为它比任何页面都活得久。
    private var statKey: String? = null
    private var statPosition = 0L
    private var statCarryMs = 0L

    init {
        // 曲目切换时才写历史。位置回调每秒好几次，不能每次都想写盘。
        viewModelScope.launch {
            player.state
                .map { it.track?.key }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null) return@collect
                    player.state.value.track?.let { track ->
                        container.recentStore.record(track)
                        refreshRecent()
                    }
                }
        }
        observeListeningTime()
        // 收藏以 Store 的 Flow 为准：通知栏（PlaybackService）改了收藏，页面这边要立刻看到
        viewModelScope.launch {
            favoritesRepo.flow.collect { snapshot ->
                _favorites.value = snapshot.keys
                _favoriteTracks.value = snapshot.tracks
            }
        }
        // 同步结果由仓库统一产出，这里只做转发，UI 的订阅面不变
        viewModelScope.launch {
            favoritesRepo.notice.collect { _likeNotice.emit(it) }
        }
    }

    // ---------------------------------------------------------------- 曲库

    fun loadLocal() {
        if (_localLoading.value) return
        _localLoading.value = true
        viewModelScope.launch {
            _local.value = runCatching { container.localMusicRepository.load() }
                .getOrDefault(emptyList())
            _localLoading.value = false
            refreshRecent()
        }
    }

    fun refreshLocal() = loadLocal()

    private fun refreshRecent() {
        _recent.value = container.recentStore.resolve(_local.value)
    }

    fun allTracks(): List<MusicTrack> = _local.value

    fun favoriteTracks(): List<MusicTrack> = _favoriteTracks.value

    // ---------------------------------------------------------------- 收藏

    /**
     * 收藏 / 取消收藏。
     *
     * 三步：本机立刻落盘（不等网络）→ 推给后端 → 后端再推给音源平台。
     * 返回切换后的状态，UI 只负责画红心；同步结果通过 [likeNotice] 异步提示。
     *
     * 实现已经下沉到 [FavoritesRepository]：PlaybackService 处理通知栏红心时走的是
     * 同一条路径，否则「通知栏收藏」和「页面收藏」会一个同步一个不同步。
     */
    fun toggleFavorite(track: MusicTrack): Boolean {
        val nowFavorite = favoritesRepo.toggle(track)
        // Flow 的收集是异步调度的，这一帧 UI 要读到新状态，所以再同步读一次
        refreshFavorites()
        return nowFavorite
    }

    fun isFavorite(key: String): Boolean = favoritesRepo.isFavorite(key)

    private fun refreshFavorites() {
        _favorites.value = container.favoritesStore.keys()
        _favoriteTracks.value = container.favoritesStore.tracks()
    }

    // ---------------------------------------------------------------- 推荐

    /**
     * 拉一次「猜你喜欢」。
     *
     * 三级策略：
     * 1. [ClientRecommendEngine] 客户端直连音源算（口味歌手聚合搜索 + 平台推荐流）
     * 2. 在线一首可播的都没有 → [LocalRecommendEngine] 只从本机曲库出歌
     * 3. 本地也空 → 上一次的缓存，标记 [recommendOffline]
     *
     * 后端不再是这条链路的一环：推荐本质是「拿这些歌手去搜」，搜得到就有得推。
     *
     * @param refresh true = 用户点「换一批」，换洗牌种子，同一天也能出不同结果
     */
    fun loadRecommendations(refresh: Boolean = false) {
        if (_recommendLoading.value) return
        if (refresh) container.playableFilter.clear()
        if (!refresh && _recommend.value.isNotEmpty() &&
            System.currentTimeMillis() - lastRecommendAt < RECOMMEND_TTL_MS
        ) {
            return
        }
        _recommendLoading.value = true
        viewModelScope.launch {
            try {
                val seed = if (refresh) System.currentTimeMillis() else null
                val artists = tasteArtists()

                // 1.1 客户端直连：搜得到就有得推，搜不到再往下走
                val online = container.clientRecommendEngine.recommend(artists, seed)
                // 换不到地址的曲目不展示：宁可少几首，也别让用户点了播不了
                val playable = container.playableFilter.filter(online.tracks)
                if (playable.isNotEmpty()) {
                    publishRecommend(
                        tracks = playable,
                        basedOn = online.basedOn,
                        reason = online.reason,
                        offline = false,
                    )
                    return@launch
                }

                // 1.2 在线全空：本机曲库接管
                val local = container.localRecommendEngine.recommend(artists, seed)
                val playableLocal = container.playableFilter.filter(local.tracks)
                if (playableLocal.isNotEmpty()) {
                    publishRecommend(
                        tracks = playableLocal,
                        basedOn = local.basedOn,
                        reason = local.reason,
                        offline = true,
                    )
                } else {
                    fallbackToCache(null)
                }
            } finally {
                _recommendLoading.value = false
            }
        }
    }

    private fun publishRecommend(
        tracks: List<MusicTrack>,
        basedOn: List<String>,
        reason: String,
        offline: Boolean,
    ) {
        _recommend.value = tracks.filterNot(container.dislikeStore::isBlocked)
        _recommendBasedOn.value = basedOn
        _recommendReason.value = reason
        _recommendOffline.value = offline
        _recommendError.value = null
        lastRecommendAt = System.currentTimeMillis()
        container.recommendCacheStore.write(tracks, basedOn, reason)
    }

    private suspend fun fallbackToCache(message: String?) {
        if (_recommend.value.isNotEmpty()) {
            // 已经有内容就不覆盖，避免刷新失败时把好列表清空
            _recommendError.value = message
            return
        }
        val cached = container.recommendCacheStore.read()
        if (cached == null) {
            _recommendError.value = message
            return
        }
        // 缓存里的在线曲目没有 uri（落盘时清过），这里也要验证一遍，
        // 否则「离线兜底」又变成一屏播不了的歌。
        val playable = container.playableFilter.filter(cached.tracks)
        if (playable.isEmpty()) {
            _recommendError.value = message
            return
        }
        _recommend.value = playable.filterNot(container.dislikeStore::isBlocked)
        _recommendBasedOn.value = cached.basedOn
        _recommendReason.value = if (cached.reason.isBlank()) "cache" else cached.reason
        _recommendOffline.value = true
        _recommendError.value = message
    }

    /**
     * 口味歌手：收藏里的歌手权重更高（计两次），再补最近播放。
     *
     * 只取前三个 —— 种子太多会让推荐变成「什么都推一点」，反而没有个性。
     */
    fun tasteArtists(): List<String> {
        val weighted = buildList {
            addAll(container.favoritesStore.tracks().map { it.artist })
            addAll(container.favoritesStore.tracks().map { it.artist })
            addAll(_recent.value.map { it.artist })
        }
        return weighted
            .filter { it.isNotBlank() && !container.dislikeStore.isSeedBlocked(it) }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)
    }

    // ---------------------------------------------------------------- 刷歌流

    /**
     * 播放 tab 的曲目流：**当前播放队列的镜像**。
     *
     * 默认只跟队列同步，不主动出推荐。只有切到 [PlayMode.RECOMMEND] 且播到
     * 队尾才往后接实时推荐。队列只追加不清空，正在播的那首不会因流变长而断。
     */
    private val _feed = MutableStateFlow<List<MusicTrack>>(emptyList())
    val feed: StateFlow<List<MusicTrack>> = _feed.asStateFlow()

    private val _feedLoading = MutableStateFlow(false)
    val feedLoading: StateFlow<Boolean> = _feedLoading.asStateFlow()

    /** 扩不出新歌了（后端不可达 / 音源全关）：页面据此提示，而不是静默卡住。 */
    private val _feedEnded = MutableStateFlow(false)
    val feedEnded: StateFlow<Boolean> = _feedEnded.asStateFlow()

    /** 已经出现过的曲目，用于去重。key 防同平台重复，title 防跨平台同名。 */
    private val feedSeenKeys = mutableSetOf<String>()
    private val feedSeenTitles = mutableSetOf<String>()

    /** 待探索歌手：刷歌的扩散器，一个歌手挖完换下一个。 */
    private val feedArtistQueue = ArrayDeque<String>()

    /** 正在换播放地址的曲目，避免同名单并发解析两次。 */
    private val resolvingKeys = mutableSetOf<String>()

    /** 已经预取过的曲目，避免每次滑页都重新写一遍缓存。 */
    private val prefetchedKeys = mutableSetOf<String>()

    private var feedJob: Job? = null

    /** 上一次扩容失败的时间，用于退避重试而不是永久放弃。 */
    private var lastFeedFailAt = 0L

    init {
        // 队列真的播到队尾时，只有「歌曲推荐」模式才续流；其余四种模式交给
        // media3 自己循环/停止。放在这里而非顶部 init：_feedEnded 到这里才初始化，
        // 顶部 init 里读它会在构造期拿到 null（踩过一次启动崩溃）。
        viewModelScope.launch {
            player.state
                .map { it.playMode to it.hasNext }
                .distinctUntilChanged()
                .collect { (mode, hasNext) ->
                    if (mode != PlayMode.RECOMMEND) {
                        // 队尾失败标记只对推荐有意义，切走就清掉，避免误弹「拿不到新歌」
                        if (_feedEnded.value) _feedEnded.value = false
                        return@collect
                    }
                    if (!hasNext) loadMoreFeed()
                }
        }
    }

    /**
     * 建流：**优先镜像当前播放队列**，不打任何推荐接口。
     *
     * 用户从推荐页/本地页/歌单页点播时，player.queue 已经是那批歌；
     * 播放页要显示的就是它，而不是另一批"猜你喜欢"。只有队列真的空了
     * （冷启动还没点过任何歌）才用本机曲库兜底——那也不是实时推荐。
     * 真正的实时推荐只发生在 [RECOMMEND] 模式 + 队尾，见 [loadMoreFeed]。
     */
    fun startFeed() {
        if (_feed.value.isNotEmpty() || feedJob?.isActive == true) return
        val queued = player.queue.value.filter { it.uri.isNotBlank() }
        if (queued.isNotEmpty()) {
            adoptQueue(queued)
            return
        }
        feedJob = viewModelScope.launch {
            _feedLoading.value = true
            resetFeedState()
            appendToFeed(fallbackFeed())
            // 从推荐页点进来的那首歌可能不在兜底里：放到第一页，用户才不会看到
            // 「正在播 A，页面却停在一首没见过的 B」
            prependNowPlaying()
            _feedEnded.value = _feed.value.isEmpty()
            _feedLoading.value = false
        }
    }

    /**
     * 从别的页面切回播放页时调用：外部点播换过队列就让 feed 跟过去。
     *
     * 刷歌追加只会在队尾加歌，不会动队首；所以比较整条列表即可，
     * 一致就说明还是同一批歌，别把用户正在滑的位置重置掉。
     */
    fun adoptQueueIfChanged() {
        if (feedJob?.isActive == true) return
        val queued = player.queue.value.filter { it.uri.isNotBlank() }
        if (queued.isEmpty()) return
        val feedKeys = _feed.value.filter { it.uri.isNotBlank() }.map { it.key }
        if (queued.map { it.key } == feedKeys) return
        adoptQueue(queued)
    }

    /** 队列曲目已经带可播地址，直接入流；顺手喂进去重和歌手扩散的种子。 */
    private fun adoptQueue(queued: List<MusicTrack>) {
        resetFeedState()
        queued.forEach { track ->
            feedSeenKeys.add(track.key)
            titleKey(track.title, track.artist)
                .takeIf { it.isNotEmpty() }
                ?.let(feedSeenTitles::add)
            enqueueArtist(mainArtist(track.artist))
        }
        _feed.value = queued
        _feedEnded.value = false
        _feedLoading.value = false
    }

    private fun prependNowPlaying() {
        val current = player.state.value.track ?: return
        if (current.uri.isBlank()) return
        if (_feed.value.any { it.key == current.key }) return
        _feed.value = listOf(current) + _feed.value
        syncFeedQueue()
    }

    /**
     * 往后接一批实时推荐。两道闸门缺一不可：
     *
     * 1. 用户开了 [PlayMode.RECOMMEND] —— 其余四种模式永远不推荐；
     * 2. 当前队列播到最后一首（`hasNext == false`）—— 队列没播完之前不插队。
     *
     * 满足后才走原来的扩流逻辑：优先用歌手队列里的下一个歌手去搜（同歌手 =
     * 最稳的「还想听」），队列空了就拿一批新的推荐结果继续拆歌手。最多试
     * [MAX_EXPLORE_ROUNDS] 轮，每一轮都被去重挡掉才算真的没歌了。
     *
     * 失败后不是永久关闭：隔 [FEED_RETRY_INTERVAL_MS] 再播到队尾会重新试，
     * 用户从地铁里出来、换了网络都能接上，不用杀进程重开。
     */
    fun loadMoreFeed() {
        if (feedJob?.isActive == true) return
        if (_feed.value.isEmpty()) return
        if (player.state.value.playMode != PlayMode.RECOMMEND) return
        if (player.state.value.hasNext) return
        if (_feedEnded.value &&
            System.currentTimeMillis() - lastFeedFailAt < FEED_RETRY_INTERVAL_MS
        ) {
            return
        }
        feedJob = viewModelScope.launch {
            _feedLoading.value = true
            var added = 0
            var rounds = 0
            while (added == 0 && rounds < MAX_EXPLORE_ROUNDS) {
                rounds++
                added = appendToFeed(nextCandidates())
            }
            if (added == 0) {
                _feedEnded.value = true
                lastFeedFailAt = System.currentTimeMillis()
            } else {
                _feedEnded.value = false
                // 续流发生时播放器可能已经停在 ENDED：加 MediaItem 不会自动起播
                player.resumeIfEnded()
            }
            _feedLoading.value = false
        }
    }

    /** 换一批：清空重来，用户主动刷新的语义。 */
    fun restartFeed() {
        feedJob?.cancel()
        _feed.value = emptyList()
        resetFeedState()
        startFeed()
    }

    /**
     * 播流里的第 [index] 首。
     *
     * 流里的曲目绝大多数在入库时已由 [PlayableFilter] 换到地址，直接播即可。
     * 这里保留一条按需换址的退路（地址过期、或从缓存恢复的曲目），
     * 换不到才回调失败，不会把一首空地址塞给 ExoPlayer。
     */
    fun playFeedAt(index: Int, onEvent: (OnlinePlayEvent) -> Unit = {}) {
        val track = _feed.value.getOrNull(index) ?: return
        if (track.uri.isNotBlank()) {
            startFeedPlayback(track)
            prefetchAfter(index)
            onEvent(OnlinePlayEvent.Ready(null))
            return
        }
        viewModelScope.launch {
            val resolved = resolvePlayable(track)
            if (resolved == null) {
                onEvent(OnlinePlayEvent.Failed(
                    getApplication<Application>().getString(R.string.play_unavailable),
                ))
                return@launch
            }
            replaceInFeed(track.key, resolved)
            startFeedPlayback(resolved)
            prefetchAfter(index)
            onEvent(OnlinePlayEvent.Ready(null))
        }
    }

    private fun resetFeedState() {
        feedSeenKeys.clear()
        feedSeenTitles.clear()
        feedArtistQueue.clear()
        prefetchedKeys.clear()
    }

    /** 推荐一批，顺手把种子歌手排进探索队列。 */
    private suspend fun recommendBatch(seed: Long): List<MusicTrack> {
        val data = container.clientRecommendEngine.recommend(
            artists = tasteArtists(),
            seed = seed,
            limit = FEED_PAGE_SIZE,
        )
        data.basedOn.forEach(::enqueueArtist)
        data.tracks.forEach { enqueueArtist(mainArtist(it.artist)) }
        return data.tracks
    }

    /** 下一批候选：先挖歌手队列，挖空了再要新推荐。 */
    private suspend fun nextCandidates(): List<MusicTrack> {
        val artist = feedArtistQueue.removeFirstOrNull()
        if (artist != null) {
            // 刷歌流和搜索页共用一条客户端直连链路，不再走 /app/music/search
            val tracks = container.onlineSourceRouter.search(artist, limit = FEED_PAGE_SIZE).tracks
            if (tracks.isNotEmpty()) return tracks
        }
        return recommendBatch(seed = System.nanoTime())
    }

    /** 后端不可达时的兜底：上一次的推荐缓存 + 本机曲库 + 收藏。 */
    private fun fallbackFeed(): List<MusicTrack> = buildList {
        container.recommendCacheStore.read()?.tracks?.let(::addAll)
        addAll(allTracks())
        addAll(favoriteTracks())
    }

    /** 去重 + 可播性验证后追加，返回真正加进去的条数。 */
    private suspend fun appendToFeed(candidates: List<MusicTrack>): Int {
        val accepted = candidates.filter { claim(it) }
        if (accepted.isEmpty()) return 0
        // 播不了的歌不进流：刷歌流是无限队列，混进一首空 uri 的歌滑到就断
        val playable = container.playableFilter.filter(accepted)
        if (playable.isEmpty()) return 0
        _feed.value = _feed.value + playable
        playable.forEach { enqueueArtist(mainArtist(it.artist)) }
        syncFeedQueue()
        return playable.size
    }

    private fun claim(track: MusicTrack): Boolean {
        if (!track.playable) return false
        if (container.dislikeStore.isBlocked(track)) return false
        val titleKey = titleKey(track.title, track.artist)
        if (titleKey.isNotEmpty() && !feedSeenTitles.add(titleKey)) return false
        if (!feedSeenKeys.add(track.key)) return false
        return true
    }

    private fun enqueueArtist(artist: String) {
        val name = artist.trim()
        if (name.isEmpty()) return
        if (container.dislikeStore.isSeedBlocked(name)) return
        if (feedArtistQueue.size >= MAX_ARTIST_QUEUE) return
        if (feedArtistQueue.contains(name)) return
        feedArtistQueue.addLast(name)
    }

    /**
     * 换播放地址。必须走 [OnlineSourceRouter]，它在后端离线时还能退回酷我直连；
     * 直接调 musicRepository.playOnline 等于把「离线也能播」这条路掐死。
     */
    private suspend fun resolvePlayable(track: MusicTrack): MusicTrack? {
        if (!resolvingKeys.add(track.key)) return null
        return try {
            container.onlineSourceRouter.play(track).track
        } finally {
            resolvingKeys.remove(track.key)
        }
    }

    private fun replaceInFeed(key: String, track: MusicTrack) {
        _feed.value = _feed.value.map { if (it.key == key) track else it }
        syncFeedQueue()
    }

    /**
     * 把「已能播的流」同步进播放器队列。
     *
     * 队列和流不是同一份数据：流里可能有还没换到地址的曲目，
     * 塞进 ExoPlayer 会直接报错。所以只同步 uri 非空的那些，
     * 剩下的等解析完再追加。
     */
    private fun syncFeedQueue() {
        player.syncQueue(_feed.value.filter { it.uri.isNotBlank() })
    }

    /** 队列里已有就直接跳，没有才重建时间线 —— 重建会打断正在播的这首。 */
    private fun startFeedPlayback(target: MusicTrack) {
        if (player.playAtKeyInQueue(target.key)) return
        val playable = _feed.value.filter { it.uri.isNotBlank() }
        val index = playable.indexOfFirst { it.key == target.key }
        if (index < 0) return
        player.play(playable, index)
    }

    /** 后两首提前拉到缓存：滑过去时是本地读，不用等网络。 */
    private fun prefetchAfter(index: Int) {
        val targets = (index + 1..index + PREFETCH_AHEAD)
            .mapNotNull { _feed.value.getOrNull(it) }
            .filter { it.uri.isNotBlank() && prefetchedKeys.add(it.key) }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { container.feedCache.prefetch(it) }
        }
    }

    private fun titleKey(title: String, artist: String): String {
        val normalized = title.lowercase()
            .replace(BRACKET_SUFFIX, "")
            .replace(PUNCTUATION, "")
            .trim()
        if (normalized.isEmpty()) return ""
        return normalized + "|" + mainArtist(artist).lowercase()
    }

    private fun mainArtist(artist: String): String {
        if (artist.isBlank()) return ""
        return artist.split('/', '、', ',', '，', '&')
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: artist.trim()
    }

    // ---------------------------------------------------------------- 我的同步

    /**
     * 把「我喜欢」和各音源账号里的歌单同步回来。
     *
     * 静默是默认行为：进前台、进推荐页、进「我的」都会调它，5 分钟内只做一次，
     * 结果只更新状态、不弹提示 —— 同步是后台维护数据，不是给用户看的事件。
     * 用户在「我的」页主动点同步时传 `notify = true`，这时才给回执（成功或失败）。
     */
    fun syncLibrary(force: Boolean = false, notify: Boolean = false) {
        if (_syncing.value) return
        if (!force && System.currentTimeMillis() - lastLibrarySyncAt < LIBRARY_SYNC_TTL_MS) return
        _syncing.value = true
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                // 离线直接跳过：这两个请求的 connect timeout 各 15 秒，串起来就是半分钟白等，
                // 而用户看到的只是「一直转圈」。收藏本来就在本机，跳过不丢任何数据。
                if (!container.onlineSourceRouter.probeNow(force = false)) {
                    if (notify) _syncNotice.emit(context.getString(R.string.sync_offline_skipped))
                    lastLibrarySyncAt = System.currentTimeMillis()
                    return@launch
                }
                val likesResult = container.musicRepository.likes(container.deviceIdStore.id)
                // 账号歌单走客户端直连：Cookie 留在本机，也不用等后端多中转一跳
                val playlists = container.onlineSourceRouter.myPlaylists()

                val restored = (likesResult as? AppResult.Success)?.data?.let {
                    container.favoritesStore.merge(it)
                } ?: 0
                if (restored > 0) refreshFavorites()

                // 空列表不覆盖上一次结果：静默同步挂一次就把「来自音源」整段清空
                // 比不刷新更糟，用户会以为歌单被删了
                if (playlists.isNotEmpty()) {
                    _syncedPlaylists.value = playlists
                }

                if (notify) {
                    val message = when {
                        likesResult is AppResult.Failure -> context.getString(
                            R.string.sync_failed, likesResult.message,
                        )

                        else -> context.getString(
                            R.string.sync_done,
                            restored,
                            playlists.size.takeIf { it > 0 } ?: _syncedPlaylists.value.size,
                        )
                    }
                    _syncNotice.emit(message)
                }
                lastLibrarySyncAt = System.currentTimeMillis()
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 打开同步回来的歌单：拉曲目后按在线队列播放。 */
    fun playSyncedPlaylist(playlist: UserPlaylistInfo, onEvent: (OnlinePlayEvent) -> Unit) {
        viewModelScope.launch {
            when (val result = container.onlineSourceRouter.playlistTracks(
                source = playlist.source,
                playlistId = playlist.id,
                name = playlist.name,
            )) {
                is AppResult.Success -> {
                    if (result.data.isEmpty()) {
                        onEvent(OnlinePlayEvent.Failed(
                            getApplication<Application>().getString(R.string.sync_playlist_empty),
                        ))
                    } else {
                        playTracks(result.data, 0, onEvent)
                    }
                }

                is AppResult.Failure -> onEvent(OnlinePlayEvent.Failed(result.message))
            }
        }
    }

    /**
     * 读音源歌单的曲目（详情页用）。
     *
     * 卡片上只有标题和总数，曲目必须点进去现拉 —— 平台上几十首歌的歌单
     * 不该在推荐页一进页面就全部下回来。
     */
    suspend fun playlistTracks(source: String, id: String, name: String): AppResult<List<MusicTrack>> {
        val result = container.onlineSourceRouter.playlistTracks(source, id, name)
        // 平台歌单里会员曲/下架曲很常见，入列表前先验证，播不了的剔除
        return when (result) {
            is AppResult.Success -> AppResult.Success(container.playableFilter.filter(result.data))
            is AppResult.Failure -> result
        }
    }

    // ---------------------------------------------------------------- 歌单分享

    /**
     * 生成分享码。
     *
     * 这是**唯一一个不降级**的功能：短码必须在服务端分配，别的设备才查得到。
     * 后端不在时直接返回失败并说明原因 —— 假装成功（比如本地生成一个码）
     * 会让用户在另一台设备上发现「码不存在」，那比直接失败更糟。
     */
    suspend fun createShareCode(title: String, tracks: List<MusicTrack>): AppResult<ShareResult> =
        container.musicRepository.createShare(container.deviceIdStore.id, title, tracks)

    /** 导入分享歌单：拉到后落本机，码本身仍留在服务端。 */
    suspend fun importShare(code: String): AppResult<SharedPlaylist> {
        val result = container.musicRepository.resolveShare(code.trim().uppercase())
        if (result is AppResult.Success) {
            container.sharedPlaylistStore.add(result.data)
            _sharedPlaylists.value = container.sharedPlaylistStore.all()
        }
        return result
    }

    /** 导入过的分享歌单。读内存副本，避免每次重绘都解析一遍 SharedPreferences。 */
    private val _sharedPlaylists =
        MutableStateFlow(container.sharedPlaylistStore.all())
    val sharedPlaylists: StateFlow<List<StoredPlaylist>> = _sharedPlaylists.asStateFlow()

    // ---------------------------------------------------------------- 歌曲评论

    private val _comments = MutableStateFlow(CommentUiState())
    val comments: StateFlow<CommentUiState> = _comments.asStateFlow()

    /** 一次性提示（点赞失败、删除失败），不占用列表状态。 */
    private val _commentNotice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val commentNotice: SharedFlow<String> = _commentNotice.asSharedFlow()

    private var commentsJob: Job? = null

    /**
     * 评论昵称：默认取设备型号。
     *
     * App 没有登录体系，强行要求填昵称只会把人劝退；但全是「匿名听众」又完全没法区分。
     * 设备型号是现成的、有区分度、且不含任何个人信息 —— 比随机昵称诚实。
     */
    private val commentNickname: String by lazy { android.os.Build.MODEL.orEmpty().trim() }

    /**
     * 拉评论。
     *
     * @param reset true = 换歌或首次打开，false = 加载更多
     */
    fun loadComments(track: MusicTrack, reset: Boolean = true) {
        if (!reset && (_comments.value.loading || !_comments.value.hasMore)) return
        commentsJob?.cancel()
        val offset = if (reset) 0 else _comments.value.items.size
        _comments.value = if (reset) {
            CommentUiState(loading = true)
        } else {
            _comments.value.copy(loading = true, error = null)
        }
        commentsJob = viewModelScope.launch {
            // 离线不做假成功也不做假空列表：评论是共享数据，本机编不出来
            if (!container.onlineSourceRouter.probeNow(force = false)) {
                _comments.value = _comments.value.copy(loading = false, error = COMMENT_OFFLINE)
                return@launch
            }
            when (val result = container.musicRepository.comments(
                track = track,
                deviceId = container.deviceIdStore.id,
                limit = COMMENT_PAGE_SIZE,
                offset = offset,
            )) {
                is AppResult.Success -> {
                    val page = result.data
                    _comments.value = _comments.value.copy(
                        loading = false,
                        items = if (reset) page.items else _comments.value.items + page.items,
                        total = page.total,
                        hasMore = page.hasMore,
                        error = null,
                    )
                }

                is AppResult.Failure -> _comments.value = _comments.value.copy(
                    loading = false,
                    error = result.message,
                )
            }
        }
    }

    /** 发表评论。回调参数是错误文案，null 表示成功。 */
    fun postComment(track: MusicTrack, content: String, onResult: (String?) -> Unit) {
        val text = content.trim()
        if (text.isEmpty()) {
            onResult(COMMENT_EMPTY)
            return
        }
        if (_comments.value.posting) return
        _comments.value = _comments.value.copy(posting = true)
        viewModelScope.launch {
            try {
                if (!container.onlineSourceRouter.probeNow(force = false)) {
                    onResult(COMMENT_OFFLINE)
                    return@launch
                }
                val result = container.musicRepository.createComment(
                    track = track,
                    deviceId = container.deviceIdStore.id,
                    nickname = commentNickname,
                    content = text,
                )
                when (result) {
                    is AppResult.Success -> {
                        _comments.value = _comments.value.copy(
                            items = listOf(result.data) + _comments.value.items,
                            total = _comments.value.total + 1,
                            error = null,
                        )
                        onResult(null)
                    }

                    is AppResult.Failure -> onResult(result.message)
                }
            } finally {
                _comments.value = _comments.value.copy(posting = false)
            }
        }
    }

    /**
     * 点赞 / 取消。
     *
     * **乐观更新**：先改本地再发请求。点赞是高频轻动作，等一次往返才变色会让人
     * 以为没点上，然后连点三次 —— 那才是真正的并发问题。失败时回滚并提示。
     */
    fun toggleCommentLike(commentId: Long) {
        val current = _comments.value.items.firstOrNull { it.id == commentId } ?: return
        val target = !current.liked
        _comments.value = _comments.value.copy(
            items = _comments.value.items.map {
                if (it.id != commentId) {
                    it
                } else {
                    it.copy(
                        liked = target,
                        likeCount = (it.likeCount + if (target) 1 else -1).coerceAtLeast(0),
                    )
                }
            },
        )
        viewModelScope.launch {
            when (val result = container.musicRepository.likeComment(
                commentId = commentId,
                deviceId = container.deviceIdStore.id,
                liked = target,
            )) {
                is AppResult.Success -> _comments.value = _comments.value.copy(
                    items = _comments.value.items.map {
                        if (it.id == commentId) {
                            it.copy(liked = result.data.liked, likeCount = result.data.likeCount)
                        } else {
                            it
                        }
                    },
                )

                is AppResult.Failure -> {
                    // 回滚到点之前的样子，别让用户以为自己赞上了
                    _comments.value = _comments.value.copy(
                        items = _comments.value.items.map {
                            if (it.id == commentId) {
                                it.copy(liked = current.liked, likeCount = current.likeCount)
                            } else {
                                it
                            }
                        },
                    )
                    _commentNotice.emit(result.message)
                }
            }
        }
    }

    /** 删除自己的评论。别人的评论后端会拒绝，这里不做本地判断。 */
    fun deleteComment(commentId: Long) {
        viewModelScope.launch {
            when (val result = container.musicRepository.deleteComment(
                commentId = commentId,
                deviceId = container.deviceIdStore.id,
            )) {
                is AppResult.Success -> _comments.value = _comments.value.copy(
                    items = _comments.value.items.filterNot { it.id == commentId },
                    total = (_comments.value.total - 1).coerceAtLeast(0),
                )

                is AppResult.Failure -> _commentNotice.emit(result.message)
            }
        }
    }

    /**
     * 播一批曲目，在线曲目先换地址。
     *
     * 和单曲点播的区别：这里会并行解析前 [BATCH_PREFETCH] 首的播放地址，
     * 再整队塞进播放器 —— 否则「播放全部」只能播一首，划走就没了。
     * 本地/云端曲目 uri 已经是可播地址，直接播，不发任何请求。
     */
    fun playTracks(
        tracks: List<MusicTrack>,
        startIndex: Int,
        onEvent: (OnlinePlayEvent) -> Unit = {},
    ) {
        val queue = tracks.drop(startIndex.coerceAtLeast(0))
        if (queue.isEmpty()) return
        if (queue.none { it.source == TrackSource.ONLINE && it.uri.isBlank() }) {
            player.play(tracks, startIndex)
            onEvent(OnlinePlayEvent.Ready(null))
            return
        }
        viewModelScope.launch {
            val resolved = coroutineScope {
                queue.take(BATCH_PREFETCH).map { track ->
                    async {
                        if (track.source == TrackSource.ONLINE && track.uri.isBlank()) {
                            container.onlineSourceRouter.play(track).track
                        } else {
                            track
                        }
                    }
                }.awaitAll()
            }.filterNotNull()

            if (resolved.isEmpty()) {
                onEvent(OnlinePlayEvent.Failed(
                    getApplication<Application>().getString(R.string.play_unavailable),
                ))
            } else {
                player.play(resolved, 0)
                onEvent(OnlinePlayEvent.Ready(null))
            }
        }
    }

    // ---------------------------------------------------------------- 播放

    fun playLocal(startIndex: Int) = player.play(_local.value, startIndex)

    fun play(tracks: List<MusicTrack>, startIndex: Int) {
        if (tracks.isNotEmpty()) player.play(tracks, startIndex)
    }

    fun toggle() = player.toggle()

    fun next() = player.next()

    /**
     * 不喜欢当前这首：记下来，之后少推同类。
     *
     * - 推荐页的列表立刻去掉被过滤的曲目；
     * - 如果这首正在播，直接切到下一首；
     * - 刷歌流里「还没播到」的同类曲目一并撤掉（播放器队列和流同步移除）。
     *   只在队列确实是刷歌流时才动队列，用户自己选的歌单队列不改。
     */
    fun dislike(track: MusicTrack) {
        val store = container.dislikeStore
        store.dislike(track)
        _recommend.value = _recommend.value.filterNot(store::isBlocked)
        feedArtistQueue.removeAll { store.isSeedBlocked(it) }

        val playingKey = playerState.value.track?.key
        val feed = _feed.value
        val feedKeys = feed.mapTo(HashSet()) { it.key }
        val queueIsFeed = player.queue.value.all { it.key in feedKeys }
        if (queueIsFeed) {
            val playingIndex = feed.indexOfFirst { it.key == playingKey }
            if (playingIndex >= 0) {
                player.removeUpcoming(store::isBlocked)
                _feed.value = feed.filterIndexed { index, item -> index <= playingIndex || !store.isBlocked(item) }
            }
        }
        if (playingKey == track.key) player.next()
    }

    fun previous() = player.previous()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun seekToFraction(fraction: Float) {
        val duration = playerState.value.durationMs
        if (duration > 0) player.seekTo((duration * fraction).toLong())
    }

    fun setVolume(volume: Float) = player.setVolume(volume)

    // ---------------------------------------------------------------- 倍速 / 定时关闭

    /** 当前倍速（0.75x 等），来自播放器状态。 */
    val speed: Float get() = playerState.value.speed

    /** 定时关闭的结束时刻（elapsedRealtime 基准），null = 未设定时。 */
    val sleepTimerEndAt: StateFlow<Long?> = player.sleepTimerEndAt

    /** 「播完当前歌曲就停」是否开启。 */
    val sleepAtEndOfTrack: StateFlow<Boolean> = player.sleepAtEndOfTrack

    fun setSpeed(speed: Float) = player.setSpeed(speed)

    /** [minutes] &gt; 0 倒计时；0 取消；-1 播完当前歌曲。 */
    fun setSleepTimer(minutes: Int) = player.setSleepTimer(minutes)

    fun cyclePlayMode(): PlayMode? = player.cyclePlayMode()

    fun playAt(index: Int) = player.playAt(index)

    // ---------------------------------------------------------------- 搜索

    /** 云端 + 本地一起搜，去重后按标题相近度粗略排序。 */
    fun search(keyword: String): List<MusicTrack> {
        val query = keyword.trim()
        if (query.isEmpty()) return emptyList()
        return allTracks()
            .filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.title.startsWith(query, ignoreCase = true) }
    }

    // ---------------------------------------------------------------- 在线搜索

    /**
     * 在线搜索，带 350ms 防抖。
     *
     * 走 [OnlineSourceRouter]：后端在线时四平台聚合，后端不在时退回本机酷我。
     * 这一层不再出现「整次失败」—— 最坏情况是空列表 + 一句原因，不会是空白页加错误弹窗。
     *
     * 输入不足两个字直接清空，避免每敲一个字母就打一次平台接口。
     */
    fun searchOnline(keyword: String) {
        val query = keyword.trim()
        onlineJob?.cancel()
        if (query.length < 2) {
            _online.value = emptyList()
            _onlineErrors.value = emptyMap()
            _onlineError.value = null
            _onlineLoading.value = false
            return
        }
        onlineQuery = query
        onlineJob = viewModelScope.launch {
            delay(350)
            _onlineLoading.value = true
            val result = container.onlineSourceRouter.search(query)
            // 请求期间用户又改了关键词：这次结果作废，不能让旧列表盖掉新输入
            if (query != onlineQuery) {
                return@launch
            }
            // 搜索结果里播不了的曲目直接不展示
            val playable = container.playableFilter.filter(result.tracks)
            _online.value = playable
            // 下划线开头的是路由层的整体状态（如 `_offline`），不是某个平台的失败，别当平台名展示
            _onlineErrors.value = result.errors.filterKeys { !it.startsWith("_") }
            _onlineError.value = when {
                playable.isNotEmpty() -> null
                result.tracks.isEmpty() -> {
                    if (container.onlineSourceRouter.backendOnline.value == false) {
                        getApplication<Application>().getString(R.string.search_offline_only_kuwo)
                    } else {
                        null
                    }
                }

                else -> getApplication<Application>().getString(R.string.search_no_playable)
            }
            _onlineLoading.value = false
        }
    }

    private var onlineQuery: String = ""

    /**
     * 点播一首在线歌曲。
     *
     * 后端在线时由后端换地址（能跨平台自动换源）；后端不在时酷我曲目走本机直连，
     * 其它平台的曲目明确提示「需要连接后端」，不假装成会员限制。
     */
    fun playOnline(track: MusicTrack, onEvent: (OnlinePlayEvent) -> Unit) {
        viewModelScope.launch {
            val playback: OnlinePlayback = container.onlineSourceRouter.play(track)
            val resolved = playback.track
            if (resolved != null) {
                player.play(listOf(resolved), 0)
                onEvent(OnlinePlayEvent.Ready(switchNotice(playback)))
            } else {
                onEvent(OnlinePlayEvent.Failed(playback.message ?: "当前不可播放"))
            }
        }
    }

    private fun switchNotice(playback: OnlinePlayback): String? {
        val to = playback.switchedTo ?: return null
        val from = playback.switchedFrom.orEmpty()
        val context = getApplication<Application>()
        return context.getString(
            R.string.source_switched_notice,
            sourceLabel(context, from),
            sourceLabel(context, to),
        )
    }

    // ---------------------------------------------------------------- 音源管理

    /** 是否已经从后端补过一次初始配置，只做一次。 */
    private var seededFromRemote = false

    /**
     * 音源配置读**本机**（[SourceConfigStore]），不再依赖 `/app/music/sources`。
     *
     * 后端的配置只用于首次初始化：补齐本机还没有的键，补完就不再往返。
     * 首帧必须有内容 —— 后端离线时音源管理页不能是空白页 + 错误提示。
     */
    fun loadSources(force: Boolean = false) {
        _sources.value = container.sourceConfigStore.infos()
        _sourceError.value = null
        _sourcesLoading.value = false
        seedSourcesIfNeeded()
    }

    private fun seedSourcesIfNeeded() {
        if (seededFromRemote) return
        seededFromRemote = true
        viewModelScope.launch {
            // 先探一下，避免后端没起时白等 15 秒 connect timeout
            if (!container.onlineSourceRouter.probeNow(force = false)) return@launch
            when (val remote = container.musicRepository.sources()) {
                is AppResult.Success -> {
                    if (container.sourceConfigStore.seedFromRemote(remote.data)) {
                        _sources.value = container.sourceConfigStore.infos()
                    }
                }

                // 离线时补不到就用默认值，不值得打扰用户
                is AppResult.Failure -> Unit
            }
        }
    }

    fun setSourceEnabled(key: String, enabled: Boolean) {
        container.sourceConfigStore.setEnabled(key, enabled)
        loadSources()
    }

    fun setSourceCookie(key: String, cookie: String) {
        val previous = container.sourceConfigStore.cookieOf(key)
        // 换了账号（包括退出登录）就先清缓存：否则下一个账号的页面
        // 会先闪一下上一个人的昵称和歌单
        if (previous != cookie) container.onlineSourceRouter.clearAccount(key)
        container.sourceConfigStore.setCookie(key, cookie)
        loadSources()
    }

    /**
     * 读取登录后的账号资料。
     *
     * 只做展示，不参与播放。缓存与节流都在 [OnlineSourceRouter.account] 里：
     * 同一次会话反复进出账号页不会重复请求，这是避免被平台风控的前提。
     */
    fun loadAccount(sourceKey: String, force: Boolean = false) {
        if (sourceKey.isBlank()) return
        val current = _accountState.value
        // 同一平台正在加载就不重复发起，防止连点刷新刷出一串请求
        if (current.loading && current.sourceKey == sourceKey) return
        _accountState.value = AccountUiState(
            sourceKey = sourceKey,
            account = if (current.sourceKey == sourceKey) current.account else null,
            loading = true,
        )
        viewModelScope.launch {
            val account = container.onlineSourceRouter.account(sourceKey, force)
            _accountState.value = AccountUiState(
                sourceKey = sourceKey,
                account = account,
                loading = false,
                failed = account == null,
            )
        }
    }

    /** 上移/下移：交换两项的 priority，语义与原后端 `PUT /sources/{key}` 一致。 */
    fun moveSource(key: String, offset: Int) {
        if (container.sourceConfigStore.move(key, offset)) loadSources()
    }

    // ---------------------------------------------------------------- 统计

    /**
     * 累计听歌时长。
     *
     * 进度已经从 [PlayerUiState] 拆出去了，所以这里改成 combine：状态（歌、是否在播）
     * 与进度（350ms 一次）合起来算增量。暂停时进度不再推进，delta 恒为 0，自然不累计。
     */
    private fun observeListeningTime() {
        viewModelScope.launch {
            combine(player.state, player.position) { state, position -> state to position }
                .collect { (state, position) ->
                    val track = state.track
                    if (track == null) {
                        statKey = null
                        statPosition = 0L
                        return@collect
                    }
                    // 切歌、暂停、拖动进度都只挪游标，不记时长
                    if (!state.isPlaying || statKey != track.key) {
                        statKey = track.key
                        statPosition = position
                        return@collect
                    }
                    val delta = position - statPosition
                    if (delta in 1..POSITION_GAP_MS) {
                        statCarryMs += delta
                        if (statCarryMs >= FLUSH_EVERY_MS) {
                            val wholeSeconds = statCarryMs / 1000
                            container.listenStatsStore.addSeconds(wholeSeconds)
                            _listenMinutes.value = container.listenStatsStore.minutes()
                            statCarryMs -= wholeSeconds * 1000
                        }
                    }
                    statPosition = position
                }
        }
    }

    private companion object {
        /** 两次位置回调的正常间隔是 350ms；超过这个数说明用户拖了进度条。 */
        const val POSITION_GAP_MS = 2_000L
        const val FLUSH_EVERY_MS = 10_000L

        /** 评论每页条数。一页够刷两次屏幕，翻页节奏刚好。 */
        const val COMMENT_PAGE_SIZE = 20
        const val COMMENT_OFFLINE = "评论需要连接后端，当前后端未连接"
        const val COMMENT_EMPTY = "说点什么再发"

        /** 推荐结果的有效期：10 分钟内反复切 tab 不重复请求。 */
        const val RECOMMEND_TTL_MS = 10 * 60 * 1000L

        /** 榜单节流 30 秒：和后台的聚合缓存同频，短时间内重复进页面不必再查。 */
        const val RANK_TTL_MS = 30_000L

        /** 「我的」页自动同步的最小间隔。 */
        const val LIBRARY_SYNC_TTL_MS = 5 * 60 * 1000L

        /** 一次最多预解析多少首在线曲目换地址；多了会明显慢。 */
        const val BATCH_PREFETCH = 5

        /** 刷歌流每次补多少首。少了要频繁请求，多了解析等待变长。 */
        const val FEED_PAGE_SIZE = 20

        /** 播放中的曲目往后预取几首到磁盘缓存。 */
        const val PREFETCH_AHEAD = 2

        /** 扩不出歌时最多试几轮（换歌手 / 换推荐批次）才判定流到底了。 */
        const val MAX_EXPLORE_ROUNDS = 4

        /** 歌手队列上限：无节制地入队只会让「下一个歌手」永远是冷门噪声。 */
        const val MAX_ARTIST_QUEUE = 30

        /** 扩容失败后多久允许再试：太短会每滑一页打一次空请求。 */
        const val FEED_RETRY_INTERVAL_MS = 30_000L

        /** 去重时先削掉 `(Live)`、`（电影版）` 这类括号后缀，跨平台才对得上。 */
        val BRACKET_SUFFIX = Regex("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*\\]")
        val PUNCTUATION = Regex("[\\s\\p{Punct}，。、！？：；“”‘’（）【】《》·—…]")
    }
}
/**
 * 在线点播的两种结局。
 *
 * Ready 也可能带 `notice`（例如「已从网易云音乐切换到酷狗音乐」），
 * 页面在打开播放页前先 toast 一下；Failed 只 toast，不进播放页。
 */
sealed interface OnlinePlayEvent {
    data class Ready(val notice: String?) : OnlinePlayEvent
    data class Failed(val message: String) : OnlinePlayEvent
}
