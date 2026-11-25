package com.szc.learning.music.data

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.core.network.music.MusicSourceInfo
import com.szc.learning.music.player.PlayerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 进程级音乐状态。
 *
 * 为什么要有这一层：`MusicViewModel` 是 Activity 作用域的，每个 Activity 各一份。
 * 曲库、最近播放、收藏、听歌时长这些**全进程只有一份真相**的状态放在 VM 里，
 * 后果是开着沉浸播放页 / 搜索页听歌时：
 * - 每次切歌写多次「最近播放」；
 * - 听歌时长按存活 Activity 数重复累计（开着沉浸页就是 2 倍）；
 * - 每个页面各扫一遍 MediaStore，内存里多份整库列表。
 *
 * 所以把「状态 + 只该执行一次的副作用」下沉到这里，挂在 `appScope` 上；
 * VM 只做转发，对外 API 不变。页面私有状态（搜索结果、评论、榜单、刷歌流）仍在 VM。
 */
class MusicSession(
    private val localMusicRepository: LocalMusicRepository,
    private val recentStore: RecentStore,
    private val favoritesStore: FavoritesStore,
    private val favoritesRepository: FavoritesRepository,
    private val listenStatsStore: ListenStatsStore,
    private val listenDiaryStore: ListenDiaryStore,
    private val sourceConfigStore: SourceConfigStore,
    private val onlineSourceRouter: OnlineSourceRouter,
    private val musicRepository: MusicRepository,
    private val player: PlayerController,
    private val scope: CoroutineScope,
) {

    private val _local = MutableStateFlow<List<MusicTrack>>(emptyList())
    val local: StateFlow<List<MusicTrack>> = _local.asStateFlow()

    private val _localLoading = MutableStateFlow(false)
    val localLoading: StateFlow<Boolean> = _localLoading.asStateFlow()

    private val _recent = MutableStateFlow<List<MusicTrack>>(emptyList())
    val recent: StateFlow<List<MusicTrack>> = _recent.asStateFlow()

    /** 收藏键。初值为空：首次解析收藏 JSON 放到 IO 上做，不占冷启动主线程。 */
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /** 收藏的完整曲目：在线曲目也在里面，不再依赖「曲库里有没有这首歌」。 */
    private val _favoriteTracks = MutableStateFlow<List<MusicTrack>>(emptyList())
    val favoriteList: StateFlow<List<MusicTrack>> = _favoriteTracks.asStateFlow()

    private val _listenMinutes = MutableStateFlow(listenStatsStore.minutes())
    val listenMinutes: StateFlow<Long> = _listenMinutes.asStateFlow()

    // ---------------------------------------------------------------- 音源配置

    private val _sources = MutableStateFlow<List<MusicSourceInfo>>(emptyList())
    val sources: StateFlow<List<MusicSourceInfo>> = _sources.asStateFlow()

    private val _sourcesLoading = MutableStateFlow(false)
    val sourcesLoading: StateFlow<Boolean> = _sourcesLoading.asStateFlow()

    private val _sourceError = MutableStateFlow<String?>(null)
    val sourceError: StateFlow<String?> = _sourceError.asStateFlow()

    /** 是否已经从后端补过一次初始配置，只做一次。 */
    private var seededFromRemote = false

    // 累计听歌时长的游标
    private var statKey: String? = null
    private var statPosition = 0L
    private var statCarryMs = 0L

    init {
        // 切歌记「最近播放」：只在这里做一次，不再随 Activity 数量翻倍
        scope.launch {
            player.state
                .map { it.track?.key }
                .distinctUntilChanged()
                .collect { key ->
                    if (key == null) return@collect
                    player.state.value.track?.let { track ->
                        recentStore.record(track)
                        refreshRecent()
                    }
                }
        }
        observeListeningTime()
        // 收藏以 Store 的 Flow 为准：通知栏（PlaybackService）改了收藏，所有页面立刻看到。
        // 放在 IO 上：首次订阅会解析整份收藏 JSON，别压在冷启动的主线程上。
        scope.launch(Dispatchers.IO) {
            favoritesRepository.flow.collect { snapshot ->
                _favorites.value = snapshot.keys
                _favoriteTracks.value = snapshot.tracks
            }
        }
    }

    // ---------------------------------------------------------------- 曲库

    /**
     * 重扫本地曲库。
     *
     * 打开页面的语义不变：**先进来就先看到上一次的结果**（[_local] 在进程内一直留着），
     * 后台照常重扫，扫出来的内容真的变了才发新值。
     */
    fun loadLocal() {
        if (_localLoading.value) return
        _localLoading.value = true
        scope.launch {
            val loaded = runCatching { localMusicRepository.load() }.getOrDefault(emptyList())
            if (_local.value != loaded) _local.value = loaded
            _localLoading.value = false
            refreshRecent()
        }
    }

    fun refreshRecent() {
        _recent.value = recentStore.resolve(_local.value)
    }

    fun refreshFavorites() {
        _favorites.value = favoritesStore.keys()
        _favoriteTracks.value = favoritesStore.tracks()
    }

    // ---------------------------------------------------------------- 音源配置

    fun loadSources(force: Boolean = false) {
        _sources.value = sourceConfigStore.infos()
        _sourceError.value = null
        _sourcesLoading.value = false
        seedSourcesIfNeeded()
    }

    private fun seedSourcesIfNeeded() {
        if (seededFromRemote) return
        seededFromRemote = true
        scope.launch {
            // 先探一下，避免后端没起时白等 15 秒 connect timeout
            if (!onlineSourceRouter.probeNow(force = false)) return@launch
            when (val remote = musicRepository.sources()) {
                is AppResult.Success -> {
                    if (sourceConfigStore.seedFromRemote(remote.data)) {
                        _sources.value = sourceConfigStore.infos()
                    }
                }

                // 离线时补不到就用默认值，不值得打扰用户
                is AppResult.Failure -> Unit
            }
        }
    }

    fun setSourceEnabled(key: String, enabled: Boolean) {
        sourceConfigStore.setEnabled(key, enabled)
        loadSources()
    }

    fun setSourceCookie(key: String, cookie: String) {
        val previous = sourceConfigStore.cookieOf(key)
        // 换了账号（包括退出登录）就先清缓存：否则下一个账号的页面
        // 会先闪一下上一个人的昵称和歌单
        if (previous != cookie) onlineSourceRouter.clearAccount(key)
        sourceConfigStore.setCookie(key, cookie)
        loadSources()
    }

    /** 上移/下移：交换两项的 priority，语义与原后端 `PUT /sources/{key}` 一致。 */
    fun moveSource(key: String, offset: Int) {
        if (sourceConfigStore.move(key, offset)) loadSources()
    }

    // ---------------------------------------------------------------- 听歌时长

    /**
     * 累计听歌时长。
     *
     * 进度已经从 `PlayerUiState` 拆出去了，所以这里 combine：状态（歌、是否在播）
     * 与进度（350ms 一次）合起来算增量。暂停时进度不再推进，delta 恒为 0。
     *
     * 计数器只有这一份，不会再出现「开着播放页按 2 倍累计」。历史值不动，
     * 见 [ListenStatsStore] 的类注释。
     */
    private fun observeListeningTime() {
        scope.launch {
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
                            listenStatsStore.addSeconds(wholeSeconds)
                            // 同一个时长写两处：一处累计（我的页），一处按天（周报）。
                            // 曲目信息跟着写，周报才不用回头去曲库里猜标题
                            listenDiaryStore.add(track, wholeSeconds)
                            _listenMinutes.value = listenStatsStore.minutes()
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
    }
}
