package com.szc.learning.music.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
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

    /**
     * 播放进度，独立于 [state]。
     *
     * 拆出来的原因：进度是唯一每 350ms 变一次的量。混在 [PlayerUiState] 里，
     * 每次 tick 都会换掉整份状态对象，所有订阅者都要跑一遍 render ——
     * 包括 `setImageResource`、`contentDescription`、文字重设这些跟进度无关的活。
     * 现在 tick 只写这里，UI 用第二个 collector 只刷进度相关控件。
     *
     * [PlayerUiState.positionMs] 仍然保留，但只在 seek / 切歌 / 暂停等**离散事件**时更新。
     */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    /** 定时关闭的结束时刻（elapsedRealtime 基准）。null = 没设定时。 */
    private val _sleepTimerEndAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndAt: StateFlow<Long?> = _sleepTimerEndAt.asStateFlow()

    /** 「播完当前歌曲就停」：没有结束时刻可以倒计时，只能单独记一位。 */
    private val _sleepAtEndOfTrack = MutableStateFlow(false)
    val sleepAtEndOfTrack: StateFlow<Boolean> = _sleepAtEndOfTrack.asStateFlow()

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
        current.setMediaItems(tracks.map { TrackMediaItem.toMediaItem(it) }, index, 0L)
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

    /**
     * 把队列对齐成 [tracks]（刷歌流专用）。
     *
     * 两种情况：
     * - 队列里出现了**不属于这批**的曲目（用户点了收藏、歌单、搜索结果）→ 直接放弃，
     *   别往他的队列里塞推荐流；
     * - 队列是这批的子序列 → 按位置补差，缺哪首插哪首。
     *
     * 补差而不是重设，是为了不打断正在播的那首；也顺手解决了「先滑过的曲目
     * 后面才解析完地址」这种顺序错位：它会被插回它该在的位置，而不是被追加到末尾。
     */
    fun syncQueue(tracks: List<MusicTrack>) {
        val current = controller ?: return
        val existing = _queue.value
        if (existing.isEmpty() || tracks.isEmpty()) return
        if (existing.size == tracks.size && existing.indices.all { existing[it].key == tracks[it].key }) {
            return
        }
        // 队列里有这批之外的曲目：这不是刷歌流的队列，不碰
        if (existing.any { queued -> tracks.none { it.key == queued.key } }) return

        var cursor = 0
        tracks.forEachIndexed { index, track ->
            if (cursor < existing.size && existing[cursor].key == track.key) {
                cursor++
            } else {
                current.addMediaItems(index, listOf(TrackMediaItem.toMediaItem(track)))
            }
        }
        _queue.value = tracks
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    /**
     * 从「还没播到」的部分移除命中的曲目。已播过的和正在播的不动 ——
     * 移除正在播的会打断播放，移除已播的会让「上一首」错位。
     */
    fun removeUpcoming(shouldRemove: (MusicTrack) -> Boolean) {
        val current = controller ?: return
        val queue = _queue.value
        val playing = current.currentMediaItemIndex
        var changed = false
        for (index in queue.indices.reversed()) {
            if (index <= playing) continue
            if (shouldRemove(queue[index])) {
                current.removeMediaItem(index)
                changed = true
            }
        }
        if (changed) {
            _queue.value = queue.filterIndexed { index, track -> index <= playing || !shouldRemove(track) }
        }
    }

    /**
     * 推荐续流的兜底：队列变长时播放器可能已经停在 ENDED，加 MediaItem 不会自动起播。
     *
     * 正常路径下追加发生在最后一首还在播的时候（hasNext 已经为 false），
     * 新歌入队后 ExoPlayer 自然往下走；这个方法只处理"已经停了"的那一帧。
     */
    fun resumeIfEnded() {
        val current = controller ?: return
        if (current.playbackState != Player.STATE_ENDED) return
        if (current.hasNextMediaItem()) {
            current.seekToNextMediaItem()
        } else if (current.mediaItemCount > 0) {
            current.seekToDefaultPosition(0)
        } else {
            return
        }
        current.prepare()
        current.play()
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
        val target = positionMs.coerceAtLeast(0L)
        controller?.seekTo(target)
        // 指尖离开进度条那一刻就要看到新位置，不能等下一个 tick
        _position.value = target
    }

    /** 音量走播放器而不是 AudioManager：后者会改动系统全局音量，退出去还得还原。 */
    fun setVolume(volume: Float) {
        controller?.volume = volume.coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- 倍速 / 定时

    /**
     * 倍速。ExoPlayer 默认保持音高，所以 1.5x 只是快，不会变调。
     *
     * 命令发给 Service 执行而不是只改本地：[倍速] 要在切歌后仍然生效，
     * 而切歌是 Service 那边的播放器在做的；只改 UI 侧等于下次切歌就丢。
     */
    fun setSpeed(speed: Float) {
        val target = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        controller?.sendCustomCommand(
            SessionCommands.setSpeed,
            Bundle().apply { putFloat(ARG_SPEED, target) },
        )
        // 乐观写入：等 Service 回调会有 1~2 帧延迟，按钮上的"1.25x"不该慢半拍
        _state.value = _state.value.copy(speed = target)
    }

    /**
     * 定时关闭。[minutes] &gt; 0 = 倒计时分钟数；0 = 关闭定时；-1 = 播完当前歌曲。
     *
     * 倒计时跑在 Service 里 —— UI 侧的对象活不过一次划掉任务卡片，
     * 而"定时关闭"最常见的用法恰恰是「放着音乐去睡觉」。
     * [sleepTimerEndAt] 只是给界面显示剩余时间的镜像，到点后由 [startPositionTicker] 自清。
     */
    fun setSleepTimer(minutes: Int) {
        controller?.sendCustomCommand(
            SessionCommands.setSleepTimer,
            Bundle().apply { putInt(ARG_MINUTES, minutes) },
        )
        _sleepTimerEndAt.value = if (minutes > 0) {
            SystemClock.elapsedRealtime() + minutes * MINUTE_MS
        } else {
            null
        }
        _sleepAtEndOfTrack.value = minutes == SLEEP_MODE_TRACK_END
    }

    /**
     * 播放方式按 顺序 → 列表循环 → 单曲循环 → 随机 → 歌曲推荐 循环切换。
     *
     * 底层还是 media3 的两个开关，第五态「歌曲推荐」靠 [PlayerUiState.recommendMode]
     * 单独记。它映射成顺序播放（不循环、不随机），这样队列播完才有 "hasNext=false"
     * 这个明确的续流时机。
     *
     * 返回值是切完之后生效的模式，UI 必须用它去显示提示，不能回头读 [state]：
     * controller 是跨进程的 MediaController，setter 只是把命令丢给 Service，
     * 真正的状态要等 binder 回来、服务端 Player 回调 onEvents 才会发布到 [state]。
     * 这段时间里读 [state] 拿到的是**旧模式** —— 图标已经晚了，提示要是也读旧值就文不对题。
     * 顺带解决了连点：以前每次点击都基于同一个旧值算 next，连点 5 次只前进一格。
     */
    fun cyclePlayMode(): PlayMode? {
        val current = controller ?: return null
        val next = state.value.playMode.next()
        val repeat = when (next) {
            PlayMode.SEQUENCE, PlayMode.RECOMMEND -> Player.REPEAT_MODE_OFF
            PlayMode.REPEAT_ALL, PlayMode.SHUFFLE -> Player.REPEAT_MODE_ALL
            PlayMode.REPEAT_ONE -> Player.REPEAT_MODE_ONE
        }
        val shuffle = next == PlayMode.SHUFFLE
        current.applyMode(repeat, shuffle = shuffle)
        // 乐观写入：让图标和提示在同一帧就对上，后面 publish() 拿到的真值会覆盖它
        _state.value = _state.value.copy(
            shuffle = shuffle,
            repeatMode = repeat,
            recommendMode = next == PlayMode.RECOMMEND,
        )
        return next
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

    /**
     * 队列里已有这首就跳过去，返回是否命中。
     *
     * 刷歌流追加曲目后队列会变长，切页时如果目标已经在队列里，
     * 用 seekToDefaultPosition 比 setMediaItems 便宜得多 —— 后者会重建时间线，
     * 正在缓冲的那首会被打断。
     */
    fun playAtKeyInQueue(key: String): Boolean {
        val current = controller ?: return false
        val index = _queue.value.indexOfFirst { it.key == key }
        if (index < 0) return false
        current.seekToDefaultPosition(index)
        current.play()
        return true
    }

    // ---------------------------------------------------------------- 内部

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                clearElapsedSleepTimer()
                val current = controller
                if (current != null && current.isPlaying) {
                    _position.value = current.currentPosition.coerceAtLeast(0L)
                    delay(POSITION_INTERVAL_PLAYING_MS)
                } else {
                    // 暂停时不再写任何东西：进度条停在最后一帧就是对的，
                    // 继续每 800ms 更新一次纯属让整页白刷
                    delay(POSITION_INTERVAL_IDLE_MS)
                }
            }
        }
    }

    /**
     * 定时到点后把镜像清掉。
     *
     * 真正的倒计时在 Service，它到点就直接暂停了，没有回执通道；
     * 这里按结束时刻自清，UI 上的"还有 N 分钟"不会挂着一个已经过期的数字。
     */
    private fun clearElapsedSleepTimer() {
        val end = _sleepTimerEndAt.value ?: return
        if (SystemClock.elapsedRealtime() >= end) _sleepTimerEndAt.value = null
    }

    /**
     * 队列只在 [play] / [syncQueue] 里写过。但有两种情况队列是"别人塞进来的"：
     * 服务被系统重启后由 `onPlaybackResumption` 恢复，或者别的控制器改过队列。
     * 那种时候 [_queue] 是空的，而播放页要拿它当数据源 —— 这里按播放器的真相补一次。
     */
    private fun hydrateQueueIfNeeded(player: Player) {
        if (_queue.value.isNotEmpty() || player.mediaItemCount == 0) return
        _queue.value = (0 until player.mediaItemCount).map {
            TrackMediaItem.toTrack(player.getMediaItemAt(it))
        }
    }

    private fun publish(player: Player) {
        val item = player.currentMediaItem
        val reportedDuration = player.duration
        val duration = when {
            reportedDuration > 0L -> reportedDuration
            item != null -> item.mediaMetadata.extras?.getLong(TrackMediaItem.KEY_DURATION) ?: 0L
            else -> 0L
        }
        // 离散事件（切歌 / seek / 暂停 / 恢复）时把进度对齐一次：
        // ticker 只管「播放中」的连续推进，这些关键时刻的跳变必须立刻反映出来
        val position = player.currentPosition.coerceAtLeast(0L)
        _position.value = position
        hydrateQueueIfNeeded(player)
        _state.value = PlayerUiState(
            track = item?.let(TrackMediaItem::toTrack),
            isPlaying = player.isPlaying,
            positionMs = position,
            durationMs = duration,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            // publish 是整份重建；推荐模式是客户端状态，media3 不会回传，必须带过去
            recommendMode = _state.value.recommendMode,
            queueSize = player.mediaItemCount,
            // 倍速不需要从旧 state 带：ExoPlayer 自己就记着，回调里读回来即可
            speed = player.playbackParameters.speed,
        )
    }

    private companion object {

        const val RESTART_THRESHOLD_MS = 3_000L
        const val POSITION_INTERVAL_PLAYING_MS = 350L
        const val POSITION_INTERVAL_IDLE_MS = 800L

        /** 倍速的可用区间。超过 2x 人声已经听不清，低于 0.5x 只剩轰鸣。 */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 3.0f
        const val MINUTE_MS = 60_000L
    }
}
