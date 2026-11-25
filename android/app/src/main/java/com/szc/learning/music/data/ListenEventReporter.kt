package com.szc.learning.music.data

import android.os.SystemClock
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.network.music.ListenEventRequest
import com.szc.learning.core.network.music.MusicRepository
import com.szc.learning.music.player.PlayerUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 播放埋点：把播放器的一生翻译成后端认得的几条事件。
 *
 * 四个约束，都是被真实场景逼出来的：
 *
 * 1. **不问用户**。埋点失败不弹任何提示 —— 用户按下播放，得到一句
 *    「上报失败」是最没有意义的反馈。失败只影响榜单，不影响听歌。
 * 2. **不排队等**。用一个容量有限的 Channel，满了丢最旧的。
 *    后端不可达时若每次上报都等 15 秒连接超时，队列会越积越长，
 *    等网络恢复时把半小时前的播放事件全灌上去，榜单直接失真。
 * 3. **连续失败就闭嘴**。三次失败后静默 60 秒，别拿无效请求打满连接池。
 * 4. **本机曲目不上报**。`content://` 只在那台设备上有效，
 *    进了榜别人点了就是一首报错的歌。云端曲目随云端曲库入口一起下线了。
 */
class ListenEventReporter(
    private val state: StateFlow<PlayerUiState>,
    private val position: StateFlow<Long>,
    private val repository: MusicRepository,
    private val deviceIds: DeviceIdStore,
    private val scope: CoroutineScope,
) {

    private val tracker = ListenTracker()

    /**
     * 待上报队列。容量 64 够装一整个歌单的切歌事件；
     * 满了丢最旧的，因为**新鲜的播放比陈旧的更有价值**。
     */
    private val queue = Channel<ListenEvent>(
        capacity = QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var started = false

    /** 熔断到期时刻（elapsedRealtime 基准）。 */
    private var mutedUntil = 0L

    private var consecutiveFailures = 0

    fun start() {
        if (started) return
        started = true

        // 单消费者：埋点不该在切歌瞬间并发打三个请求，串行也就够快了
        scope.launch {
            for (event in queue) {
                deliver(event)
            }
        }

        scope.launch {
            combine(state, position) { ui, pos -> Triple(ui.track, pos, ui.isPlaying) }
                .collect { (track, pos, playing) ->
                    tracker.sample(track, pos, playing).forEach(::enqueue)
                }
        }
    }

    /** 收藏红心：`like` 是强正向信号，直接记一条，不经过播放器。 */
    fun reportLike(track: MusicTrack) {
        enqueue(ListenEvent(track, ListenEventKind.LIKE, 0L))
    }

    // ---------------------------------------------------------------- 内部

    private fun enqueue(event: ListenEvent) {
        if (!reportable(event.track)) return
        if (SystemClock.elapsedRealtime() < mutedUntil) return
        queue.trySend(event)
    }

    private suspend fun deliver(event: ListenEvent) {
        val track = event.track
        val result = repository.reportListenEvent(
            ListenEventRequest(
                deviceId = deviceIds.id,
                source = track.sourceKey,
                trackId = track.id,
                title = track.title,
                artist = track.artist,
                cover = track.artUri,
                durationMs = track.durationMs,
                event = event.kind.wire,
                positionMs = event.positionMs,
            ),
        )
        when (result) {
            is AppResult.Success -> consecutiveFailures = 0
            is AppResult.Failure -> {
                consecutiveFailures++
                if (consecutiveFailures >= MAX_FAILURES_BEFORE_MUTE) {
                    mutedUntil = SystemClock.elapsedRealtime() + MUTE_MS
                    consecutiveFailures = 0
                }
            }
        }
    }

    private fun reportable(track: MusicTrack): Boolean = when (track.source) {
        TrackSource.LOCAL -> false
        TrackSource.CLOUD -> false
        TrackSource.ONLINE -> track.sourceKey.isNotBlank() && track.id.isNotBlank()
    }

    private companion object {
        const val QUEUE_CAPACITY = 64

        /** 连续失败几次开始熔断。后端关机时不至于每条都撞一次超时。 */
        const val MAX_FAILURES_BEFORE_MUTE = 3
        const val MUTE_MS = 60_000L
    }
}
