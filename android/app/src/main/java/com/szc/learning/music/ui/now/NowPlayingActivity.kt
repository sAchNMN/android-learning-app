package com.szc.learning.music.ui.now

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.core.common.HudToast
import com.szc.learning.ui.theme.themeColor
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ActivityNowPlayingBinding
import com.szc.learning.music.data.LrcParser
import com.szc.learning.music.data.LyricLine
import com.szc.learning.music.data.LyricSheet
import com.szc.learning.music.player.PlayMode
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.comments.CommentSheet
import com.szc.learning.music.ui.common.CoverColors
import com.szc.learning.music.ui.common.CoverPalette
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.bindBackdropArt
import com.szc.learning.music.ui.common.iconRes
import com.szc.learning.music.ui.common.labelRes
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springHaptic
import com.szc.learning.music.ui.motion.springPop
import com.szc.learning.music.ui.common.showQueueSheet
import com.szc.learning.music.ui.widget.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 正在播放。
 *
 * 深色是刻意的：从播放器页滑进来、或从浅色首页点开，都进入同一个"看演出"的状态。
 * 背景是**封面主色派生的一对深色做纵向渐变**，上面再垫一层 160px 小图放大（alpha 0.35），
 * 换歌时颜色在 600ms 内过渡 —— 所有 API 级别效果一致，不再对大图做 GPU 模糊，
 * 也不再有「低版本是清晰大图」的割裂感。
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val viewModel: MusicViewModel by viewModels()

    private var boundKey: String? = null
    private var currentTrack: MusicTrack? = null
    private var lyricJob: Job? = null
    private var lyricSheet: LyricSheet? = null

    /** 当前曲目时长，给进度比例用（来自 [PlayerUiState]，随离散状态更新）。 */
    private var durationMs = 0L

    /** 上一次画过的离散状态：值没变就别再 setImageResource。 */
    private var renderedPlaying: Boolean? = null
    private var renderedMode: PlayMode? = null

    /** 定时关闭的镜像：endAt 为 null 时不显示倒计时（可能只是「播完当前就停」）。 */
    private var sleepEndAt: Long? = null
    private var sleepAtEndOfTrack = false
    private var sleepHintJob: Job? = null

    /** 背景渐变的当前两色（动画过程中不断更新），换歌时从这里出发插值。 */
    private var gradientTop: Int = CoverPalette.Fallback.top
    private var gradientBottom: Int = CoverPalette.Fallback.bottom
    private var backdropAnimator: ValueAnimator? = null
    private val argbEvaluator = ArgbEvaluator()

    /** 只建一次，换色用 setColors，避免每帧分配新 drawable。 */
    private val backdropGradient = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(gradientTop, gradientBottom),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题要在 super 之前套上：晚一步，窗口会先按清单里的默认主题创建
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        // 放在 super 之后：AppCompat 这时才把亮暗写进 resources，
        // enableEdgeToEdge 读到的才是最终模式，状态栏图标不会反色
        enableEdgeToEdge()
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.nowBackdropGradient.background = backdropGradient

        // 导航栏保持透明：不要系统给手势条下面加那层白色蒙版
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.nowRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            binding.nowTopBar.updatePadding(top = bars.top + view.context.dp(8f).toInt())
            insets
        }

        binding.nowSeek.showKnob = true
        binding.nowSeek.onDark = true
        binding.nowSeek.onSeekFinished = { fraction -> viewModel.seekToFraction(fraction) }

        binding.nowCollapse.setOnClickListener { finish() }
        binding.nowPlay.setOnClickListener { viewModel.toggle() }
        binding.nowNext.setOnClickListener { viewModel.next() }
        binding.nowPrev.setOnClickListener { viewModel.previous() }
        binding.nowMode.setOnClickListener {
            // 图标小，切完给个文字回执，否则用户不知道点到了哪种模式。
            // 必须用返回的新模式：state 要等 media3 回调才更新，这一帧读到的还是旧的
            val mode = viewModel.cyclePlayMode() ?: return@setOnClickListener
            toast(mode.labelRes, mode.iconRes)
        }
        binding.nowQueue.setOnClickListener { showQueue() }
        binding.nowMore.setOnClickListener { showMenu() }
        binding.nowComments.setOnClickListener { showComments() }
        binding.nowDislike.setOnClickListener {
            currentTrack?.let {
                viewModel.dislike(it)
                toast(R.string.player_disliked)
            }
        }
        binding.nowFavorite.setOnClickListener { currentTrack?.let { toggleFavorite(it) } }
        binding.nowLyric.onLineClick = { line -> handleLyricClick(line) }
        listOf(
            binding.nowCollapse,
            binding.nowDislike,
            binding.nowComments,
            binding.nowQueue,
            binding.nowPlay,
            binding.nowNext,
            binding.nowPrev,
            binding.nowMode,
            binding.nowFavorite,
            binding.nowMore,
        ).forEach { it.installSpringPress(0.86f) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state -> render(state) }
            }
        }
        // 进度单独订阅：只刷时间文字、进度条、歌词高亮，不走整页 render
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.position.collect { position -> renderPosition(position) }
            }
        }
        // 通知栏点红心时，页面上的心形也要跟着变（收藏的唯一真相源在 Store）
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favorites.collect {
                    currentTrack?.let(::renderFavorite)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sleepTimerEndAt.collect { endAt ->
                    sleepEndAt = endAt
                    renderSleepHint()
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sleepAtEndOfTrack.collect { atEnd ->
                    sleepAtEndOfTrack = atEnd
                    renderSleepHint()
                }
            }
        }
    }

    /**
     * 在线曲目没有内嵌歌词，走 `/app/music/lyric`；本地/云端仍由仓库解析。
     * 带时间码的歌词进入滚动视图，纯文本歌词则静态居中，点一下看全文。
     */
    private fun loadLyric(track: MusicTrack) {
        lyricJob?.cancel()
        lyricSheet = null
        binding.nowLyric.showPlaceholder("")
        lyricJob = lifecycleScope.launch {
            val text = viewModel.lyrics(track)
            // 加载期间已经切歌：这次结果作废，不能盖掉新歌的歌词
            if (currentTrack?.key != track.key) return@launch
            val sheet = LrcParser.parse(text)
            lyricSheet = sheet
            if (sheet.isEmpty) {
                binding.nowLyric.showPlaceholder("")
            } else {
                binding.nowLyric.setSheet(sheet, viewModel.position.value)
            }
        }
    }

    private fun handleLyricClick(line: LyricLine) {
        if (line.timeMs >= 0) {
            viewModel.seekTo(line.timeMs)
            return
        }
        // 没有时间码就没法跳转，退回成「点开看全文」
        val text = lyricSheet?.lines?.joinToString("\n") { it.text }
        if (!text.isNullOrBlank()) showLyrics(text)
    }

    private fun showLyrics(text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.player_lyrics_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun render(state: PlayerUiState) {
        val track = state.track
        if (track == null) {
            // 在线曲目是先 startActivity 再等 MediaController 回传当前曲目：
            // 队列已经建好但状态还没到的那一帧不能直接关页，否则用户点完歌只看到一闪
            if (state.queueSize == 0) {
                finish()
            }
            return
        }
        val changed = currentTrack?.key != track.key
        currentTrack = track
        if (changed) loadLyric(track)

        durationMs = state.durationMs
        binding.nowTitle.text = track.title
        binding.nowArtist.text = track.artist.ifBlank {
            getString(R.string.player_unknown_artist)
        }
        binding.nowDuration.text = formatDuration(state.durationMs)

        if (renderedPlaying != state.isPlaying) {
            renderedPlaying = state.isPlaying
            binding.nowPlay.setImageResource(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
            binding.nowPlay.contentDescription =
                getString(if (state.isPlaying) R.string.player_pause else R.string.player_play)
        }

        val mode = state.playMode
        if (renderedMode != mode) {
            renderedMode = mode
            binding.nowMode.setImageResource(mode.iconRes)
            binding.nowMode.contentDescription = getString(
                R.string.player_mode_switch,
                getString(mode.labelRes),
            )
        }

        if (boundKey != track.key) {
            boundKey = track.key
            binding.nowCover.bindArt(track, sizePx = 900)
            // 背景不用 1400px 大图：160px 小图放大本身就是柔化，低版本和高版本观感一致
            binding.nowBackdrop.bindBackdropArt(track)
            renderFavorite(track)
            // 换歌时先把进度画一次，不用等下一个 tick
            renderPosition(viewModel.position.value)
            lifecycleScope.launch {
                val colors = CoverPalette.load(this@NowPlayingActivity, track)
                // 取色是异步的（读图 + 聚类）；期间已经切歌就作废，否则旧歌的颜色会盖到新歌上
                if (currentTrack?.key != track.key) return@launch
                animateBackdropTo(colors)
            }
        }
    }

    /**
     * 进度相关的全部 UI 都只在这里更新。
     *
     * 三条约束：
     * - 用户正拖着进度条时不覆盖他的位置（否则圆点在指下抖）
     * - 时长未知（还在解析）时比例算 0，不画 NaN
     * - 歌词只喂进度，行切换由 LyricView 自己判
     */
    private fun renderPosition(positionMs: Long) {
        binding.nowPosition.text = formatDuration(positionMs)
        if (!binding.nowSeek.isSeeking) {
            binding.nowSeek.progress = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        binding.nowLyric.updateProgress(positionMs)
    }

    /**
     * 背景换色：600ms 减速过渡。
     *
     * 每次换歌直接跳颜色会很生硬（尤其连着切歌时像在闪屏），所以用 ArgbEvaluator
     * 在两对颜色之间插值。GradientDrawable 只建一次、改颜色用 setColors，
     * 避免每帧新建 drawable 造成 GC 抖动。
     */
    private fun animateBackdropTo(colors: CoverColors) {
        if (colors.top == gradientTop && colors.bottom == gradientBottom) return
        backdropAnimator?.cancel()
        val fromTop = gradientTop
        val fromBottom = gradientBottom
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BACKDROP_FADE_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedFraction
                gradientTop = argbEvaluator.evaluate(fraction, fromTop, colors.top) as Int
                gradientBottom = argbEvaluator.evaluate(fraction, fromBottom, colors.bottom) as Int
                applyGradient()
            }
            start()
        }
    }

    private fun applyGradient() {
        backdropGradient.setColors(intArrayOf(gradientTop, gradientBottom))
    }

    private fun renderFavorite(track: MusicTrack) {
        val favorite = viewModel.isFavorite(track.key)
        binding.nowFavorite.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
        )
        binding.nowFavorite.imageTintList = ColorStateList.valueOf(
            themeColor(if (favorite) R.attr.auLike else R.attr.auOnDark),
        )
    }

    private fun toggleFavorite(track: MusicTrack) {
        val favorite = viewModel.toggleFavorite(track)
        renderFavorite(track)
        // 收藏是"心跳"：先缩一下，再用一次明显过冲弹回来
        binding.nowFavorite.springPop(
            startScale = if (favorite) 0.66f else 0.82f,
            durationSeconds = if (favorite) 0.62f else 0.46f,
            bounce = if (favorite) 0.52f else 0.3f,
        )
        binding.nowFavorite.springHaptic()
        toast(if (favorite) R.string.player_added_favorite else R.string.player_removed_favorite)
    }

    private fun showQueue() = showQueueSheet(this, viewModel)

    /** 播放设置：定时关闭 + 倍速。低频操作都收在这里，不挤占底部控制条。 */
    private fun showMenu() {
        NowPlayingMenuSheet(
            activity = this,
            viewModel = viewModel,
            onSleepChanged = { minutes ->
                val message = when (minutes) {
                    SLEEP_OFF -> getString(R.string.sleep_cancelled)
                    SLEEP_TRACK_END -> getString(R.string.sleep_applied_track_end)
                    else -> getString(
                        R.string.sleep_applied,
                        getString(R.string.sleep_minutes, minutes),
                    )
                }
                toastMessage(message)
            },
            onSpeedChanged = { speed ->
                toastMessage(getString(R.string.speed_applied, formatSpeed(speed)))
            },
        ).show()
    }

    /** 0.75 → "0.75"，1.0 → "1"：整数倍速不带小数点更干净。 */
    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    /**
     * 定时关闭的剩余时间。
     *
     * 每秒重算一次，而不是让 ViewModel 每秒发一条状态：这里只影响一个小 TextView，
     * 没必要让整份播放状态跟着抖。定时到点后 [PlayerController] 会把镜像清空，
     * 这个提示自然消失。
     */
    private fun renderSleepHint() {
        sleepHintJob?.cancel()
        val endAt = sleepEndAt
        if (endAt == null && !sleepAtEndOfTrack) {
            binding.nowSleepHint.visibility = View.GONE
            return
        }
        binding.nowSleepHint.visibility = View.VISIBLE
        if (endAt == null) {
            binding.nowSleepHint.text = getString(R.string.sleep_track_end)
            return
        }
        sleepHintJob = lifecycleScope.launch {
            while (isActive) {
                val remaining = endAt - SystemClock.elapsedRealtime()
                if (remaining <= 0L) {
                    binding.nowSleepHint.visibility = View.GONE
                    return@launch
                }
                binding.nowSleepHint.text =
                    getString(R.string.sleep_remaining, formatDuration(remaining))
                delay(1000L)
            }
        }
    }

    /**
     * 打开评论弹层。
     *
     * 没有正在播放的曲目时不弹空壳 —— 评论是「对这首歌」的，没有歌就没有对象。
     */
    private fun showComments() {
        val track = currentTrack
        if (track == null) {
            toast(R.string.comment_no_track)
            return
        }
        CommentSheet(this, viewModel, track).show()
    }

    /**
     * 播放页底部被进度条和传输控制占了，提示往上抬一点，别压在按钮上。
     */
    private fun toast(@StringRes resId: Int, @DrawableRes iconRes: Int = 0) {
        HudToast.show(this, getString(resId), iconRes, bottomMarginDp = 210)
    }

    private fun toastMessage(message: CharSequence) {
        HudToast.show(this, message, bottomMarginDp = 210)
    }

    override fun onDestroy() {
        sleepHintJob?.cancel()
        sleepHintJob = null
        backdropAnimator?.cancel()
        backdropAnimator = null
        super.onDestroy()
    }

    private companion object {
        /** 换歌时背景换色时长：短了像闪屏，长了像反应慢。600ms 是「跟着歌呼吸」的节奏。 */
        const val BACKDROP_FADE_MS = 600L

        /** 与 PlayerController.setSleepTimer 的约定一致。 */
        const val SLEEP_OFF = 0
        const val SLEEP_TRACK_END = -1
    }
}