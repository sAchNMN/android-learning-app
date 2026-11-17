package com.szc.learning.music.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.szc.learning.LearningApp
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.player.PlayerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 曲库与播放的共享 ViewModel（Activity 作用域）。
 *
 * 四个 tab 都要读同一份曲库，所以不能各建一个：否则切 tab 就重新扫一遍 MediaStore，
 * 而且"最近播放"在每个 tab 里各自一套。这里统一持有。
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

    /** 当前播放队列。正在播放页的"队列"弹层直接读它。 */
    val queue: StateFlow<List<MusicTrack>> = player.queue

    private val _cloudLoading = MutableStateFlow(false)
    val cloudLoading: StateFlow<Boolean> = _cloudLoading.asStateFlow()

    private val _localLoading = MutableStateFlow(false)
    val localLoading: StateFlow<Boolean> = _localLoading.asStateFlow()

    private val _cloudError = MutableStateFlow<String?>(null)
    val cloudError: StateFlow<String?> = _cloudError.asStateFlow()

    /** 播放器状态直接转出去，页面不需要知道 PlayerController 的存在。 */
    val playerState: StateFlow<PlayerUiState> = player.state

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

    private fun refreshRecent() {
        _recent.value = container.recentStore.resolve(cloud.value + local.value)
    }

    // ---------------------------------------------------------------- 播放

    fun playCloud(startIndex: Int) = player.play(cloud.value, startIndex)

    fun playLocal(startIndex: Int) = player.play(local.value, startIndex)

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
        return (cloud.value + local.value)
            .filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artist.contains(query, ignoreCase = true) ||
                    track.album.contains(query, ignoreCase = true)
            }
            .distinctBy { it.key }
            .sortedByDescending { it.title.startsWith(query, ignoreCase = true) }
    }

    /** 首页/搜索里的混合推荐源：云端优先，不够再用本地补齐。 */
    fun mix(limit: Int = 6): List<MusicTrack> =
        (cloud.value + local.value).distinctBy { it.key }.take(limit)
}
