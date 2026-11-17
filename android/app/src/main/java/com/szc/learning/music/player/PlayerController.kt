package com.szc.learning.music.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 播放器的应用级门面。
 *
 * 它是 UI 与 media3 之间唯一的接触面：
 * - UI 只订阅 [state]，不直接碰 MediaController
 * - 连接是异步的，未连上时所有操作静默忽略，UI 不会崩
 * - 位置不是事件驱动的，所以自己起一个 350ms 的轮询（暂停时降到 800ms 省电）
 *
 * 注意：不要在这里持有 Activity 的 Context，否则播放活着而页面已销毁时就是内存泄漏。
 */
class PlayerController(context: Context, private val scope: CoroutineScope) {

    private val appContext: Context = context.applicationContext

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java),
        ),
    ).buildAsync()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<List<MusicTrack>>(emptyList())
    val queue: StateFlow<List<MusicTrack>> = _queue.asStateFlow()

    @Volatile
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    init {
        controllerFuture.addListener(
            {
                val connected = runCatching { controllerFuture.get() }.getOrNull()
                    ?: return@addListener
                controller = connected
                connected.addListener(listener)
                publish(connected)
                startPositionTicker()
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    // ---------------------------------------------------------------- 播放控制

    /** 整队播放：列表页点某一行时，把整个列表作为队列塞进去，而不是只播一首。 */
    fun play(tracks: List<MusicTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val current = controller ?: return
        _queue.value = tracks
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        current.setMediaItems(tracks.map { it.toMediaItem() }, index, 0L)
        current.prepare()
        current.play()
    }

    fun toggle() {
        val current = controller ?: return
        if (current.isPlaying) {
            current.pause()
        } else {
            if (current.playbackState == Player.STATE_IDLE) current.prepare()
            current.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /** 播放超过 3 秒时，"上一首"按 iOS 习惯回到本曲开头，而不是真的切上一首。 */
    fun previous() {
        val current = controller ?: return
        if (current.currentPosition > RESTART_THRESHOLD_MS) {
            current.seekTo(0L)
        } else {
            current.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /** 音量走播放器而不是 AudioManager：后者会改动系统全局音量，退出去还得还原。 */
    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    fun toggleShuffle() {
        val current = controller ?: return
        current.shuffleModeEnabled = !current.shuffleModeEnabled
    }

    /** 关 → 列表循环 → 单曲循环，三态循环，和 iOS 一致。 */
    fun cycleRepeat() {
        val current = controller ?: return
        current.repeatMode = when (current.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** 点队列里的某一项：直接跳到对应索引继续播。 */
    fun playAt(index: Int) {
        controller?.seekToDefaultPosition(index)
    }

    // ---------------------------------------------------------------- 内部

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val current = controller
                if (current != null && current.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = current.currentPosition.coerceAtLeast(0L),
                        isPlaying = true,
                    )
                    delay(POSITION_INTERVAL_PLAYING_MS)
                } else {
                    delay(POSITION_INTERVAL_IDLE_MS)
                }
            }
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val reportedDuration = player.duration
        val duration = when {
            reportedDuration > 0L -> reportedDuration
            item != null -> item.mediaMetadata.extras?.getLong(KEY_DURATION) ?: 0L
            else -> 0L
        }
        _state.value = PlayerUiState(
            track = item?.toTrack(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            queueSize = player.mediaItemCount,
        )
    }

    private fun MusicTrack.toMediaItem(): MediaItem {
        val extras = Bundle().apply {
            putLong(KEY_DURATION, durationMs)
            putString(KEY_ALBUM, album)
            putString(KEY_ART, artUri)
            putString(KEY_SOURCE, source.name)
        }
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artUri?.let(Uri::parse))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun MediaItem.toTrack(): MusicTrack {
        val meta = mediaMetadata
        val extras = meta.extras
        val source = extras?.getString(KEY_SOURCE)
            ?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() }
            ?: TrackSource.LOCAL
        return MusicTrack(
            id = mediaId.substringAfter(':', mediaId),
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = extras?.getString(KEY_ALBUM).orEmpty(),
            durationMs = extras?.getLong(KEY_DURATION) ?: 0L,
            uri = localConfiguration?.uri?.toString().orEmpty(),
            artUri = extras?.getString(KEY_ART),
            source = source,
        )
    }

    private companion object {
        const val KEY_DURATION = "aurora.duration"
        const val KEY_ALBUM = "aurora.album"
        const val KEY_ART = "aurora.art"
        const val KEY_SOURCE = "aurora.source"

        const val RESTART_THRESHOLD_MS = 3_000L
        const val POSITION_INTERVAL_PLAYING_MS = 350L
        const val POSITION_INTERVAL_IDLE_MS = 800L
    }
}
