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
import com.szc.learning.core.network.music.UserPlaylistInfo
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.common.sourceLabel
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

    private val _cloud = MutableStateFlow<List<MusicTrack>>(emptyList())
    val cloud: StateFlow<List<MusicTrack>> = _cloud.asStateFlow()

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

    // ---------------------------------------------------------------- 同步

    private val _syncedPlaylists = MutableStateFlow<List<UserPlaylistInfo>>(emptyList())
    val syncedPlaylists: StateFlow<List<UserPlaylistInfo>> = _syncedPlaylists.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private var lastLibrarySyncAt = 0L

    /** 收藏同步的一次性提示（已同步到哪个平台 / 为什么没同步上）。 */
    private val _likeNotice = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val likeNotice: SharedFlow<String> = _likeNotice.asSharedFlow()

    private val _listenMinutes = MutableStateFlow(container.listenStatsStore.minutes())
    val listenMinutes: StateFlow<Long> = _listenMinutes.asStateFlow()

    /** 当前播放队列。正在播放页的"队列"弹层直接读它。 */
    val queue: StateFlow<List<MusicTrack>> = player.queue

    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading: StateFlow<Boolean> = _cloudLoading.asStateFlow()

    private val _localLoading = MutableStateFlow(false)
    val localLoading: StateFlow<Boolean> = _localLoading.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

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

    /** 播放器状态直接转出去，页面不需要知道 PlayerController 的存在。 */
    val playerState: StateFlow<PlayerUiState> = player.state

    /** 读取当前曲目的歌词；在线曲目走向后端 `/app/music/lyric`，本地/云端仍走原逻辑。 */
    suspend fun lyrics(track: MusicTrack): String? =
        if (track.source == TrackSource.ONLINE) {
            container.musicRepository.onlineLyric(track)
        } else {
            container.lyricsRepository.lyrics(track)
        }

    // 累计听歌时长的游标。放在 ViewModel 里是因为它比任何页面都活得久。
    private var statKey: String? = null
    private var statPosition = 0L
    private var statCarryMs = 0L

    init {
        loadCloud()
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
    }

    // ---------------------------------------------------------------- 曲库

    fun loadCloud(force: Boolean = false) {
        if (_cloudLoading.value) return
        if (!force && _cloud.value.isNotEmpty()) return
        _cloudLoading.value = true
        viewModelScope.launch {
            when (val result = container.musicRepository.tracks()) {
                is AppResult.Success -> {
                    _cloud.value = result.data
                    _cloudError.value = null
                }

                is AppResult.Failure -> {
                    _cloudError.value = result.message
                }
            }
            _cloudLoading.value = false
            refreshRecent()
        }
    }

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
        _recent.value = container.recentStore.resolve(_cloud.value + _local.value)
    }

    fun allTracks(): List<MusicTrack> = (_cloud.value + _local.value).distinctBy { it.key }

    fun favoriteTracks(): List<MusicTrack> = _favoriteTracks.value

    // ---------------------------------------------------------------- 收藏

    /**
     * 收藏 / 取消收藏。
     *
     * 三步：本机立刻落盘（不等网络）→ 推给后端 → 后端再推给音源平台。
     * 返回切换后的状态，UI 只负责画红心；同步结果通过 [likeNotice] 异步提示。
     */
    fun toggleFavorite(track: MusicTrack): Boolean {
        val nowFavorite = container.favoritesStore.toggle(track)
        refreshFavorites()
        syncLike(track, nowFavorite)
        return nowFavorite
    }

    fun isFavorite(key: String): Boolean = key in _favorites.value

    private fun refreshFavorites() {
        _favorites.value = container.favoritesStore.keys()
        _favoriteTracks.value = container.favoritesStore.tracks()
    }

    private fun syncLike(track: MusicTrack, liked: Boolean) {
        viewModelScope.launch {
            val result = container.musicRepository.setLike(container.deviceIdStore.id, track, liked)
            val context = getApplication<Application>()
            when (result) {
                is AppResult.Success -> {
                    val data = result.data
                    // 只对在线音源给出同步提示：本地/云端本来就没有「平台」可同步
                    if (track.source != TrackSource.ONLINE) return@launch
                    val ok = data.syncs.filter { it.ok }
                    val failed = data.syncs.filterNot { it.ok }
                    val message = when {
                        !liked -> null
                        ok.isNotEmpty() -> context.getString(
                            R.string.like_synced,
                            ok.joinToString("、") { sourceLabel(context, it.source) },
                        )

                        failed.isNotEmpty() -> context.getString(
                            R.string.like_local_only,
                            failed.first().message,
                        )

                        !data.saved -> context.getString(R.string.like_backend_missing)
                        else -> null
                    }
                    message?.let { _likeNotice.emit(it) }
                }

                is AppResult.Failure -> {
                    // 本机/云端曲目只做口味记录，后端挂了不该弹「同步失败」吓人
                    if (liked && track.source == TrackSource.ONLINE) {
                        _likeNotice.emit(
                            context.getString(R.string.like_sync_failed, result.message),
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 推荐

    /**
     * 拉一次「猜你喜欢」。
     *
     * 口味种子由本机算（收藏 + 最近播放里的歌手），断网时退回上一次的本地缓存并标记
     * [recommendOffline]；缓存也没有才把错误交给页面。
     *
     * @param refresh true = 用户点「换一批」，换洗牌种子，同一天也能出不同结果
     */
    fun loadRecommendations(refresh: Boolean = false) {
        if (_recommendLoading.value) return
        if (!refresh && _recommend.value.isNotEmpty() &&
            System.currentTimeMillis() - lastRecommendAt < RECOMMEND_TTL_MS
        ) {
            return
        }
        _recommendLoading.value = true
        viewModelScope.launch {
            val seed = if (refresh) System.currentTimeMillis() else null
            val result = container.musicRepository.recommend(
                deviceId = container.deviceIdStore.id,
                artists = tasteArtists(),
                seed = seed,
            )
            when (result) {
                is AppResult.Success -> {
                    if (result.data.tracks.isEmpty()) {
                        fallbackToCache(result.data.reason)
                    } else {
                        _recommend.value = result.data.tracks
                        _recommendBasedOn.value = result.data.basedOn
                        _recommendReason.value = result.data.reason
                        _recommendOffline.value = false
                        _recommendError.value = null
                        lastRecommendAt = System.currentTimeMillis()
                        container.recommendCacheStore.write(
                            result.data.tracks,
                            result.data.basedOn,
                            result.data.reason,
                        )
                    }
                }

                is AppResult.Failure -> fallbackToCache(result.message)
            }
            _recommendLoading.value = false
        }
    }

    private fun fallbackToCache(message: String?) {
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
        _recommend.value = cached.tracks
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
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
            .take(3)
    }

    // ---------------------------------------------------------------- 我的同步

    /**
     * 把「我喜欢」和各音源账号里的歌单同步回来。
     *
     * 默认 5 分钟内只做一次，进「我的」页会自动触发；
     * 用户手动点同步时传 force = true。
     */
    fun syncLibrary(force: Boolean = false) {
        if (_syncing.value) return
        if (!force && System.currentTimeMillis() - lastLibrarySyncAt < LIBRARY_SYNC_TTL_MS) return
        _syncing.value = true
        viewModelScope.launch {
            val context = getApplication<Application>()
            val likesResult = container.musicRepository.likes(container.deviceIdStore.id)
            val playlistsResult = container.musicRepository.userPlaylists()

            val restored = (likesResult as? AppResult.Success)?.data?.let {
                container.favoritesStore.merge(it)
            } ?: 0
            if (restored > 0) refreshFavorites()

            val playlists = (playlistsResult as? AppResult.Success)?.data.orEmpty()
            _syncedPlaylists.value = playlists

            _syncMessage.value = when {
                likesResult is AppResult.Failure -> context.getString(
                    R.string.sync_failed, likesResult.message,
                )

                playlistsResult is AppResult.Failure -> context.getString(
                    R.string.sync_failed, playlistsResult.message,
                )

                else -> context.getString(
                    R.string.sync_done,
                    restored,
                    playlists.size,
                )
            }
            lastLibrarySyncAt = System.currentTimeMillis()
            _syncing.value = false
        }
    }

    /** 打开同步回来的歌单：拉曲目后按在线队列播放。 */
    fun playSyncedPlaylist(playlist: UserPlaylistInfo, onEvent: (OnlinePlayEvent) -> Unit) {
        viewModelScope.launch {
            when (val result = container.musicRepository.playlistTracks(
                playlist.source, playlist.id, playlist.name,
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
                            (container.musicRepository.playOnline(track) as? AppResult.Success)?.data?.track
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

    fun playCloud(startIndex: Int) = player.play(_cloud.value, startIndex)

    fun playLocal(startIndex: Int) = player.play(_local.value, startIndex)

    fun play(tracks: List<MusicTrack>, startIndex: Int) {
        if (tracks.isNotEmpty()) player.play(tracks, startIndex)
    }

    fun toggle() = player.toggle()

    fun next() = player.next()

    fun previous() = player.previous()

    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun seekToFraction(fraction: Float) {
        val duration = playerState.value.durationMs
        if (duration > 0) player.seekTo((duration * fraction).toLong())
    }

    fun setVolume(volume: Float) = player.setVolume(volume)

    fun cyclePlayMode() = player.cyclePlayMode()

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
     * 在线聚合搜索，带 350ms 防抖。
     *
     * 输入不足两个字直接清空，避免每敲一个字母就打一次平台接口
     * （Mineradio 的搜索也是回车/停顿后才请求）。
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
            val result = container.musicRepository.searchOnline(query)
            // 请求期间用户又改了关键词：这次结果作废，不能让旧列表盖掉新输入
            if (query != onlineQuery) {
                return@launch
            }
            when (result) {
                is AppResult.Success -> {
                    _online.value = result.data.tracks
                    _onlineErrors.value = result.data.errors
                    _onlineError.value = null
                }

                is AppResult.Failure -> {
                    _online.value = emptyList()
                    _onlineErrors.value = emptyMap()
                    _onlineError.value = result.message
                }
            }
            _onlineLoading.value = false
        }
    }

    private var onlineQuery: String = ""

    /**
     * 点播一首在线歌曲：先向服务端换地址（服务端会在失败时自动换源），
     * 成功后才把真实 uri 填进 [MusicTrack] 交给播放器。
     */
    fun playOnline(track: MusicTrack, onEvent: (OnlinePlayEvent) -> Unit) {
        viewModelScope.launch {
            when (val result = container.musicRepository.playOnline(track)) {
                is AppResult.Success -> {
                    val playback = result.data
                    val resolved = playback.track
                    if (resolved != null) {
                        player.play(listOf(resolved), 0)
                        onEvent(OnlinePlayEvent.Ready(switchNotice(playback)))
                    } else {
                        onEvent(OnlinePlayEvent.Failed(playback.message ?: "当前不可播放"))
                    }
                }

                is AppResult.Failure -> onEvent(OnlinePlayEvent.Failed(result.message))
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

    fun loadSources(force: Boolean = false) {
        if (_sourcesLoading.value) return
        if (!force && _sources.value.isNotEmpty()) return
        _sourcesLoading.value = true
        viewModelScope.launch {
            when (val result = container.musicRepository.sources()) {
                is AppResult.Success -> {
                    _sources.value = result.data
                    _sourceError.value = null
                }

                is AppResult.Failure -> _sourceError.value = result.message
            }
            _sourcesLoading.value = false
        }
    }

    fun setSourceEnabled(key: String, enabled: Boolean) {
        viewModelScope.launch {
            if (persistSource(key, enabled = enabled)) loadSources(force = true)
        }
    }

    fun setSourceCookie(key: String, cookie: String) {
        viewModelScope.launch {
            if (persistSource(key, cookie = cookie)) loadSources(force = true)
        }
    }

    /** 上移/下移：交换两项的 priority，再整体重载，避免本地顺序和库里的优先级打架。 */
    fun moveSource(key: String, offset: Int) {
        val current = _sources.value.sortedBy { it.priority }
        val index = current.indexOfFirst { it.key == key }
        if (index < 0) return
        val target = index + offset
        if (target !in current.indices) return
        val first = current[index]
        val second = current[target]
        viewModelScope.launch {
            persistSource(first.key, priority = second.priority)
            persistSource(second.key, priority = first.priority)
            loadSources(force = true)
        }
    }

    private suspend fun persistSource(
        key: String,
        enabled: Boolean? = null,
        priority: Int? = null,
        cookie: String? = null,
    ): Boolean = when (
        val result = container.musicRepository.updateSource(
            key = key,
            enabled = enabled,
            priority = priority,
            cookie = cookie,
        )
    ) {
        is AppResult.Success -> true
        is AppResult.Failure -> {
            _sourceError.value = result.message
            false
        }
    }

    // ---------------------------------------------------------------- 统计

    private fun observeListeningTime() {
        viewModelScope.launch {
            player.state.collect { state ->
                val track = state.track
                if (track == null) {
                    statKey = null
                    statPosition = 0L
                    return@collect
                }
                // 切歌、暂停、拖动进度都只挪游标，不记时长
                if (!state.isPlaying || statKey != track.key) {
                    statKey = track.key
                    statPosition = state.positionMs
                    return@collect
                }
                val delta = state.positionMs - statPosition
                if (delta in 1..POSITION_GAP_MS) {
                    statCarryMs += delta
                    if (statCarryMs >= FLUSH_EVERY_MS) {
                        val wholeSeconds = statCarryMs / 1000
                        container.listenStatsStore.addSeconds(wholeSeconds)
                        _listenMinutes.value = container.listenStatsStore.minutes()
                        statCarryMs -= wholeSeconds * 1000
                    }
                }
                statPosition = state.positionMs
            }
        }
    }

    private companion object {
        /** 两次位置回调的正常间隔是 350ms；超过这个数说明用户拖了进度条。 */
        const val POSITION_GAP_MS = 2_000L
        const val FLUSH_EVERY_MS = 10_000L

        /** 推荐结果的有效期：10 分钟内反复切 tab 不重复请求。 */
        const val RECOMMEND_TTL_MS = 10 * 60 * 1000L

        /** 「我的」页自动同步的最小间隔。 */
        const val LIBRARY_SYNC_TTL_MS = 5 * 60 * 1000L

        /** 一次最多预解析多少首在线曲目换地址；多了会明显慢。 */
        const val BATCH_PREFETCH = 5
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
