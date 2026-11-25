package com.szc.learning.music.player

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.szc.learning.LearningApp
import com.szc.learning.R
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.music.data.FavoritesRepository
import com.szc.learning.music.data.QueueSnapshot
import com.szc.learning.music.data.QueueSnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * 后台播放服务。
 *
 * 播放器必须活在这个 Service 里而不是 Activity：Activity 一销毁播放就断，
 * 那不是「音乐播放软件」。
 *
 * MediaSessionService 会自动把 MediaSession 暴露给：
 * - 本进程的 [PlayerController]（MediaController 通过 SessionToken 连接）
 * - 通知栏 / 锁屏 / 蓝牙耳机
 * - 车机、手表等外部控制器
 *
 * 所以 UI 层完全不用自己写通知，也不用处理按键事件。
 *
 * 通知栏额外挂了一颗「喜欢」按钮（自定义会话命令），收藏的写入口是
 * [FavoritesRepository] —— 与 App 内点红心走同一条路，否则会出现
 * 「通知栏收藏了但没同步到后端」这种两套真相。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var serviceScope: CoroutineScope? = null

    /** 定时关闭的倒计时任务。重新设定时先取消旧的，避免两个计时同时到点。 */
    private var sleepJob: Job? = null

    /**
     * 保留 ExoPlayer 的强类型引用：`pauseAtEndOfMediaItems` 只在 ExoPlayer 上有，
     * `mediaSession.player` 拿到的是 `Player` 接口，看不到它。
     */
    private var exoPlayer: ExoPlayer? = null

    /** 收藏的唯一入口，见类注释。 */
    private val favorites: FavoritesRepository
        get() = (application as LearningApp).container.favoritesRepository

    /** 播放现场（队列 / 索引 / 位置）的落盘，见 [restoreQueue]。 */
    private val snapshotStore: QueueSnapshotStore
        get() = (application as LearningApp).container.queueSnapshotStore

    /** 读存档用。DataStore 是挂起 API，不能在主线程上等它。 */
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /** 刷歌流的音频缓存，和 ViewModel 的预取共用同一份（SimpleCache 单实例约束）。 */
    private val feedCache: FeedAudioCache
        get() = (application as LearningApp).container.feedCache

    /**
     * 播放现场落盘用的协程作用域。
     *
     * 不能用 [serviceScope]：它在 `onDestroy` 里被取消，会把正在写的那份快照一起掐掉。
     * 队列的 `toTrack` 与 JSON 序列化都在这里的后台线程上做，主线程只取一次不可变列表。
     */
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 队列内容的版本号：只有时间线因换队列 / 追加 / 删除而变化时才 +1。 */
    private var playlistVersion = 0

    /** 等写盘的最新现场。300ms 内的多次变化合并成一次。 */
    private var pendingSnapshot: SnapshotRequest? = null
    private var snapshotJob: Job? = null

    /** 上一次真正写过的内容标识（队列版本, 索引, 位置），用于跳过重复落盘。 */
    private var lastWrittenSnapshot: SnapshotKey? = null

    private data class SnapshotRequest(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val version: Int,
    )

    private data class SnapshotKey(val version: Int, val index: Int, val positionMs: Long)

    /** 通知栏「喜欢」按钮的当前输入：同一首歌、同一个收藏状态就不重设布局。 */
    private var lastLikeButton: Pair<String, Boolean>? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机时自动暂停，否则会突然外放
            .setHandleAudioBecomingNoisy(true)
            // 熄屏后别让系统把 CPU / Wi-Fi 睡掉：在线曲目播到一半缓冲两个字就被掐断，
            // 表现就是「亮着屏没事，锁屏十分钟回来歌停了」。WAKE_MODE_NETWORK 只在
            // 需要网络时持锁，不是全程亮屏。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 走缓存数据源：播过的写盘、预取过的直接本地读，滑到下一首不用等网络
            .setMediaSourceFactory(feedCache.mediaSourceFactory())
            .build()
        exoPlayer = player

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(sessionCallback)
            .build()
            .also { refreshLikeButton(it) }

        // 服务被单独拉起（通知栏 / 蓝牙续播）时可能没有页面来触发连接：
        // 让门面也连上，播放埋点与听歌时长才能继续工作。已有连接时是空操作。
        (application as LearningApp).container.playerController.connect()

        // 换歌后心形要立刻切到新歌的状态；顺便把播放现场落盘（续播要用）
        player.addListener(
            object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    refreshLikeButton()
                    persistSnapshot(withPosition = false)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    refreshLikeButton()
                }

                override fun onTimelineChanged(
                    timeline: androidx.media3.common.Timeline,
                    reason: Int,
                ) {
                    // SOURCE_UPDATE 只是时长等元数据就绪，队列内容没变，快照逐字节相同，不落盘
                    if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
                    playlistVersion++
                    updateWakeMode()
                    persistSnapshot(withPosition = false)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 暂停这一刻的位置最值得记：用户下次点播放就该从这儿继续
                    if (!isPlaying) persistSnapshot(withPosition = true, immediate = true)
                }
            },
        )

        // 页面里点红心 → 通知栏图标跟着变
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                favorites.flow.collect { refreshLikeButton() }
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {

        /**
         * 自定义命令必须在连接时声明，否则按钮点了会被当成「不支持的命令」。
         * 通知栏控制器、蓝牙、以及本进程的 PlayerController 都走这里。
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val available = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .apply { SessionCommands.all.forEach { add(it) } }
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(available)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: androidx.media3.session.SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_TOGGLE_LIKE -> {
                    val item = session.player.currentMediaItem
                        ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
                    val track = TrackMediaItem.toTrack(item)
                    favorites.toggle(track)
                    // 通知栏图标本身就是回执（实心/空心马上变），不在后台再弹一条提示：
                    // Service 里拿不到 Activity，退回系统 Toast 是排队播放的，还会在 13+ 被后台限制吃掉
                    refreshLikeButton(session)
                }

                COMMAND_SET_SLEEP_TIMER -> armSleepTimer(args.getInt(ARG_MINUTES, 0))

                COMMAND_SET_SPEED -> {
                    val speed = args.getFloat(ARG_SPEED, 1f)
                    session.player.setPlaybackSpeed(speed.coerceIn(0.5f, 3f))
                }

                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * 进程被杀后，蓝牙耳机 / 通知栏点播放会走到这里。
         *
         * 这时是新进程，内存里什么都没有，只能读磁盘上的播放现场。
         * 读 DataStore 是挂起操作，所以整件事丢到单线程 executor 上做，不阻塞主线程。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.submit(Callable { restoreQueue() }, ioExecutor)
    }

    /**
     * 定时关闭。
     *
     * 计时放在 Service 而不是 UI：用户设完定时通常会划掉任务卡片，
     * UI 侧的协程那一刻就没了，而"听着睡"恰恰是这个功能唯一的场景。
     *
     * [SLEEP_MODE_TRACK_END] 用 `pauseAtEndOfMediaItems` 实现 —— 它是「当前这首播完就停住」，
     * 不会出现下一首响起半秒再被掐断的粗暴效果。
     */
    private fun armSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        val player = exoPlayer ?: return
        player.pauseAtEndOfMediaItems = minutes == SLEEP_MODE_TRACK_END
        if (minutes <= 0) return
        sleepJob = serviceScope?.launch {
            delay(minutes * 60_000L)
            fadeOutAndPause()
        }
    }

    /** 3 秒音量淡出再暂停：突然一刀切断会把人吵醒，这功能就白做了。 */
    private suspend fun fadeOutAndPause() {
        val player = exoPlayer ?: return
        val from = player.volume
        val steps = FADE_STEPS
        repeat(steps) { index ->
            player.volume = from * (1f - (index + 1f) / steps)
            delay(FADE_STEP_MS)
        }
        player.pause()
        // 还原音量：下次点播放不该是静音的
        player.volume = from
        player.pauseAtEndOfMediaItems = false
    }

    /**
     * 重画通知栏的自定义按钮。
     *
     * 只有「当前曲目 + 是否已收藏」两个输入：没有曲目时给空布局，
     * 别在通知栏上留一颗点了没反应的心的。
     *
     * 每次播放状态变化（缓冲 ↔ 就绪）都会走到这里，而 media3 每设一次布局
     * 就会推给所有控制器、通知随之重建。输入没变就跳过。
     */
    private fun refreshLikeButton(session: MediaSession? = mediaSession) {
        val target = session ?: return
        val item = target.player.currentMediaItem
        if (item == null) {
            if (lastLikeButton != null) {
                lastLikeButton = null
                target.setCustomLayout(emptyList())
            }
            return
        }
        val liked = favorites.isFavorite(item.mediaId)
        val key = item.mediaId to liked
        if (key == lastLikeButton) return
        lastLikeButton = key
        val button = CommandButton.Builder()
            .setDisplayName(
                getString(if (liked) R.string.player_unlike else R.string.player_like),
            )
            .setIconResId(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
            .setSessionCommand(SessionCommands.toggleLike)
            .build()
        target.setCustomLayout(listOf(button))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * 恢复上次的队列与位置。
     *
     * **只恢复本地/云端曲目**：在线曲目的 URL 是点播时才向服务端换来的，
     * 落盘时已被清空（见 FavoritesStore.persist 的同类处理），恢复出来只会是点不动的灰条。
     * 宁可队列短一点，也不要恢复出一堆"点了没反应"的条目 —— 这一点写在汇报里。
     */
    private fun restoreQueue(): MediaSession.MediaItemsWithStartPosition {
        val snapshot = runBlocking { snapshotStore.read() }
            ?: return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        val playable = snapshot.tracks.filter {
            it.source != TrackSource.ONLINE && it.uri.isNotBlank()
        }
        if (playable.isEmpty()) {
            return MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L)
        }
        val currentKey = snapshot.tracks.getOrNull(snapshot.index)?.key
        val restoredIndex = playable.indexOfFirst { it.key == currentKey }
        val startIndex = if (restoredIndex >= 0) restoredIndex else 0
        // 找不到当初那首（它是在线曲目）时从第一首开始，位置也归零，别把 A 歌的进度套到 B 歌上
        val startPosition = if (restoredIndex >= 0) snapshot.positionMs else 0L
        return MediaSession.MediaItemsWithStartPosition(
            playable.map { TrackMediaItem.toMediaItem(it) },
            startIndex,
            startPosition,
        )
    }

    /**
     * 落盘当前播放现场。
     *
     * [withPosition] 只有暂停时才为 true：切歌/换队列时上一首的位置已经没有意义，
     * 记 0 才是对的。
     *
     * 主线程只做一次不可变快照（MediaItem 列表 + 索引/位置），`toTrack` 与 `toJson`
     * 都在 [snapshotScope] 的后台线程上做 —— 切歌那一帧正是封面、歌词最忙的时候。
     * [immediate] 为 true 时立即写（暂停 / 划掉任务 / 销毁），忽略 300ms 合并窗口。
     */
    private fun persistSnapshot(withPosition: Boolean, immediate: Boolean = false) {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) return
        pendingSnapshot = SnapshotRequest(
            items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) },
            index = player.currentMediaItemIndex,
            positionMs = if (withPosition) player.currentPosition.coerceAtLeast(0L) else 0L,
            version = playlistVersion,
        )
        if (immediate) writePendingSnapshot() else scheduleSnapshotWrite()
    }

    private fun scheduleSnapshotWrite() {
        if (snapshotJob?.isActive == true) return
        // 合并窗口挂在 serviceScope（主线程）上，真正的重活才丢到 IO
        snapshotJob = serviceScope?.launch {
            delay(SNAPSHOT_COALESCE_MS)
            writePendingSnapshot()
        }
    }

    private fun writePendingSnapshot() {
        snapshotJob?.cancel()
        snapshotJob = null
        val request = pendingSnapshot ?: return
        pendingSnapshot = null
        val key = SnapshotKey(request.version, request.index, request.positionMs)
        // 内容没变（单曲循环的 transition、缓冲/就绪来回切）就不重写整份文件
        if (key == lastWrittenSnapshot) return
        lastWrittenSnapshot = key
        snapshotScope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                QueueSnapshot(
                    tracks = request.items.map(TrackMediaItem::toTrack),
                    index = request.index,
                    positionMs = request.positionMs,
                )
            }
            snapshotStore.write(snapshot)
        }
    }

    /**
     * 按队列里有没有网络曲目切换持锁模式。
     *
     * `WAKE_MODE_NETWORK` 会在播放时持一个 WifiLock；纯本地队列根本不需要它，
     * 开着只会白耗电。混合队列保持 NETWORK —— 在线那几首播到一半不能被断网掐掉。
     */
    private fun updateWakeMode() {
        val player = exoPlayer ?: return
        var network = false
        for (index in 0 until player.mediaItemCount) {
            val scheme = player.getMediaItemAt(index).localConfiguration?.uri?.scheme
            if (scheme == "http" || scheme == "https") {
                network = true
                break
            }
        }
        player.setWakeMode(if (network) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
    }

    /** 用户从最近任务里划掉应用：没在播就顺手结束服务，别留一个空进程。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务卡片前先记一笔：这是「进程下一秒可能就没了」的唯一时机，不等合并窗口
        persistSnapshot(withPosition = true, immediate = true)
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 先把最后一份现场落盘再放手播放器；写盘在独立的 IO 作用域上，不会被取消
        persistSnapshot(withPosition = true, immediate = true)
        sleepJob?.cancel()
        sleepJob = null
        serviceScope?.cancel()
        serviceScope = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        // 播放器已经放手，这时删缓存文件不会和写入打架。
        // 服务结束＝本次会话的刷歌结束，音频不留到下一次。
        feedCache.clear()
        ioExecutor.shutdown()
        super.onDestroy()
    }
}

/** 淡出参数：30 步 × 100ms = 3 秒，从当前音量线性降到 0。 */
private const val FADE_STEPS = 30
private const val FADE_STEP_MS = 100L

/** 快照合并窗口：300ms 内的多次变化只在最后写一次。 */
private const val SNAPSHOT_COALESCE_MS = 300L
