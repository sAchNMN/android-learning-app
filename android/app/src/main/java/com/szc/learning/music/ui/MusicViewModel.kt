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
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.common.sourceLabel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun favoriteTracks(): List<MusicTrack> {
        val favorites = _favorites.value
        return allTracks().filter { it.key in favorites }
    }

    // ---------------------------------------------------------------- 收藏

    fun toggleFavorite(track: MusicTrack): Boolean {
        val nowFavorite = container.favoritesStore.toggle(track.key)
        _favorites.value = container.favoritesStore.keys()
        return nowFavorite
    }

    fun isFavorite(key: String): Boolean = key in _favorites.value

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

    fun toggleShuffle() = player.toggleShuffle()

    fun setVolume(volume: Float) = player.setVolume(volume)
    fun cycleRepeat() = player.cycleRepeat()

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

    /** 首页/搜索里的混合推荐源：云端优先，不够再用本地补齐。 */
    fun mix(limit: Int = 6): List<MusicTrack> = allTracks().take(limit)

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
