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
                // 冷启动默认列表循环：顺序播放播完就停，容易被当成"播放器坏了"
                connected.repeatMode = Player.REPEAT_MODE_ALL
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

    /**
     * 播放方式按 顺序 → 列表循环 → 单曲循环 → 随机 循环切换。
     *
     * 底层还是 media3 的两个开关，但这里一次只改一个组合，
     * 保证「随机」和「循环」不会同时处于用户看得见的高亮态。
     */
    fun cyclePlayMode() {
        val current = controller ?: return
        when (state.value.playMode.next()) {
            PlayMode.SEQUENCE -> current.applyMode(Player.REPEAT_MODE_OFF, shuffle = false)
            PlayMode.REPEAT_ALL -> current.applyMode(Player.REPEAT_MODE_ALL, shuffle = false)
            PlayMode.REPEAT_ONE -> current.applyMode(Player.REPEAT_MODE_ONE, shuffle = false)
            PlayMode.SHUFFLE -> current.applyMode(Player.REPEAT_MODE_ALL, shuffle = true)
        }
    }

    /** 先关随机再改循环：顺序反过来的话，中间那一帧会出现两个都亮的状态。 */
    private fun Player.applyMode(repeat: Int, shuffle: Boolean) {
        if (!shuffle && shuffleModeEnabled) shuffleModeEnabled = false
        repeatMode = repeat
        if (shuffle && !shuffleModeEnabled) shuffleModeEnabled = true
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
            putString(KEY_FOLDER, folder)
            // 在线曲目从 MediaItem 还原时必须靠这些字段，不能从 mediaId 反推：
            // ONLINE 的 key 是 "ONLINE:平台:id"，直接 substringAfter 会把平台名当成 id 的一部分
            putString(KEY_TRACK_ID, id)
            putString(KEY_SOURCE_KEY, sourceKey)
            putString(KEY_HASH, hash)
            putString(KEY_ALBUM_ID, albumId)
            putString(KEY_ALBUM_AUDIO_ID, albumAudioId)
            putBoolean(KEY_PLAYABLE, playable)
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
            id = extras?.getString(KEY_TRACK_ID) ?: mediaId.substringAfter(':', mediaId),
            title = meta.title?.toString().orEmpty(),
            artist = meta.artist?.toString().orEmpty(),
            album = extras?.getString(KEY_ALBUM).orEmpty(),
            durationMs = extras?.getLong(KEY_DURATION) ?: 0L,
            uri = localConfiguration?.uri?.toString().orEmpty(),
            artUri = extras?.getString(KEY_ART),
            source = source,
            folder = extras?.getString(KEY_FOLDER).orEmpty(),
            sourceKey = extras?.getString(KEY_SOURCE_KEY).orEmpty(),
            hash = extras?.getString(KEY_HASH).orEmpty(),
            albumId = extras?.getString(KEY_ALBUM_ID).orEmpty(),
            albumAudioId = extras?.getString(KEY_ALBUM_AUDIO_ID).orEmpty(),
            playable = extras?.getBoolean(KEY_PLAYABLE, true) ?: true,
        )
    }

    private companion object {
        const val KEY_DURATION = "aurora.duration"
        const val KEY_ALBUM = "aurora.album"
        const val KEY_ART = "aurora.art"
        const val KEY_SOURCE = "aurora.source"
        const val KEY_FOLDER = "aurora.folder"
        const val KEY_TRACK_ID = "aurora.trackId"
        const val KEY_SOURCE_KEY = "aurora.sourceKey"
        const val KEY_HASH = "aurora.hash"
        const val KEY_ALBUM_ID = "aurora.albumId"
        const val KEY_ALBUM_AUDIO_ID = "aurora.albumAudioId"
        const val KEY_PLAYABLE = "aurora.playable"

        const val RESTART_THRESHOLD_MS = 3_000L
        const val POSITION_INTERVAL_PLAYING_MS = 350L
        const val POSITION_INTERVAL_IDLE_MS = 800L
    }
}
