package com.szc.learning.music.ui.now

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.szc.learning.R
import com.szc.learning.core.common.HudToast
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ActivityImmersivePlayerBinding
import com.szc.learning.music.data.LrcParser
import com.szc.learning.music.data.LyricLine
import com.szc.learning.music.data.LyricSheet
import com.szc.learning.music.player.PlayMode
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.CoverPalette
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.bindBackdropArt
import com.szc.learning.music.ui.common.iconRes
import com.szc.learning.music.ui.common.labelRes
import com.szc.learning.music.ui.common.showQueueSheet
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springHaptic
import com.szc.learning.music.ui.motion.springPop
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.ui.theme.themeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 沉浸式全屏播放。
 *
 * 和播放 tab 共用同一份全局播放状态，只是把这一页做成「舞台」：
 * 放大封面的柔光 + 封面主色光晕 + 缓慢上浮的微尘 + 居中的滚动歌词；
 * 系统状态栏和导航栏全部隐藏，从顶部下滑可临时唤出。
 *
 * 横竖屏各一套布局：竖屏封面在上、歌词居中；横屏封面在左、歌词在右。
 * 两套布局使用同一组 id，代码不区分方向。
 */
class ImmersivePlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImmersivePlayerBinding
    private val viewModel: MusicViewModel by viewModels()

    private var currentTrack: MusicTrack? = null
    private var boundKey: String? = null
    private var lyricJob: Job? = null
    private var lyricSheet: LyricSheet? = null
    private var durationMs = 0L
    private var renderedPlaying: Boolean? = null
    private var renderedMode: PlayMode? = null
    private var backdropAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 看演出时屏幕不要熄
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityImmersivePlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        enterImmersiveMode()

        ViewCompat.setOnApplyWindowInsetsListener(binding.immersiveContent) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val horizontal = view.context.dp(24f).toInt()
            view.updatePadding(
                left = horizontal + bars.left,
                top = view.context.dp(8f).toInt() + bars.top,
                right = horizontal + bars.right,
                bottom = view.context.dp(12f).toInt() + bars.bottom,
            )
            insets
        }

        binding.immersiveSeek.apply {
            showKnob = true
            onDark = true
            onSeekFinished = { fraction -> viewModel.seekToFraction(fraction) }
        }

        binding.immersiveClose.setOnClickListener { finish() }
        binding.immersiveQueue.setOnClickListener { showQueueSheet(this, viewModel) }
        binding.immersiveMore.setOnClickListener { showMenu() }
        binding.immersivePlay.setOnClickListener { viewModel.toggle() }
        binding.immersiveNext.setOnClickListener { viewModel.next() }
        binding.immersivePrev.setOnClickListener { viewModel.previous() }
        binding.immersiveMode.setOnClickListener {
            val mode = viewModel.cyclePlayMode() ?: return@setOnClickListener
            toast(mode.labelRes, mode.iconRes)
        }
        binding.immersiveFavorite.setOnClickListener {
            currentTrack?.let { toggleFavorite(it) }
        }
        binding.immersiveLyric.onLineClick = { line -> handleLyricClick(line) }
        listOf(
            binding.immersiveClose,
            binding.immersiveQueue,
            binding.immersiveMore,
            binding.immersivePlay,
            binding.immersiveNext,
            binding.immersivePrev,
            binding.immersiveMode,
            binding.immersiveFavorite,
        ).forEach { it.installSpringPress(0.86f) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state -> render(state) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.position.collect { position -> renderPosition(position) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favorites.collect { currentTrack?.let(::renderFavorite) }
            }
        }
        startBackdropBreath()
    }

    /**
     * 真正全屏：系统栏隐藏，边缘手势区下滑可临时唤出，几秒后自动缩回。
     * 每次窗口重新获得焦点都再收一次，避免被输入法或转屏打断后留下系统栏。
     */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onPause() {
        backdropAnimator?.pause()
        binding.immersiveStage.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        backdropAnimator?.resume()
        binding.immersiveStage.resume()
        enterImmersiveMode()
    }

    /** 背景缓慢呼吸：16 秒一个来回，让静态封面也有「在场」的感觉。 */
    private fun startBackdropBreath() {
        if (Motion.animationsDisabled(this)) return
        backdropAnimator = ValueAnimator.ofFloat(1f, BACKDROP_SCALE).apply {
            duration = BACKDROP_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                binding.immersiveBackdrop.scaleX = scale
                binding.immersiveBackdrop.scaleY = scale
            }
            start()
        }
    }

    private fun loadLyric(track: MusicTrack) {
        lyricJob?.cancel()
        lyricSheet = null
        binding.immersiveLyric.showPlaceholder("")
        lyricJob = lifecycleScope.launch {
            val text = viewModel.lyrics(track)
            if (currentTrack?.key != track.key) return@launch
            val sheet = LrcParser.parse(text, viewModel.lyricOffset(track.key))
            lyricSheet = sheet
            if (sheet.isEmpty) {
                binding.immersiveLyric.showPlaceholder("")
            } else {
                binding.immersiveLyric.setSheet(sheet, viewModel.position.value)
            }
        }
    }

    private fun handleLyricClick(line: LyricLine) {
        if (line.timeMs >= 0) {
            viewModel.seekTo(line.timeMs)
            return
        }
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
            if (state.queueSize == 0) finish()
            return
        }
        val changed = currentTrack?.key != track.key
        currentTrack = track
        if (changed) loadLyric(track)

        durationMs = state.durationMs
        binding.immersiveTitle.text = track.title
        binding.immersiveArtist.text = track.artist.ifBlank {
            getString(R.string.player_unknown_artist)
        }
        binding.immersiveDuration.text = formatDuration(state.durationMs)

        if (renderedPlaying != state.isPlaying) {
            renderedPlaying = state.isPlaying
            binding.immersivePlay.setImageResource(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
            )
            binding.immersivePlay.contentDescription =
                getString(if (state.isPlaying) R.string.player_pause else R.string.player_play)
        }

        val mode = state.playMode
        if (renderedMode != mode) {
            renderedMode = mode
            binding.immersiveMode.setImageResource(mode.iconRes)
            binding.immersiveMode.contentDescription = getString(
                R.string.player_mode_switch,
                getString(mode.labelRes),
            )
        }

        if (boundKey != track.key) {
            boundKey = track.key
            binding.immersiveCover.bindArt(track, sizePx = 900)
            binding.immersiveBackdrop.bindBackdropArt(track, sizePx = 220, alpha = 0.32f)
            renderFavorite(track)
            renderPosition(viewModel.position.value)
            lifecycleScope.launch {
                val colors = CoverPalette.load(this@ImmersivePlayerActivity, track)
                if (currentTrack?.key != track.key) return@launch
                binding.immersiveStage.setColors(colors)
            }
        }
    }

    private fun renderPosition(positionMs: Long) {
        binding.immersivePosition.text = formatDuration(positionMs)
        if (!binding.immersiveSeek.isSeeking) {
            binding.immersiveSeek.progress = if (durationMs > 0L) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
        }
        binding.immersiveLyric.updateProgress(positionMs)
    }

    private fun renderFavorite(track: MusicTrack) {
        val favorite = viewModel.isFavorite(track.key)
        binding.immersiveFavorite.setImageResource(
            if (favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart,
        )
        binding.immersiveFavorite.imageTintList = ColorStateList.valueOf(
            themeColor(if (favorite) R.attr.auLike else R.attr.auOnDark),
        )
    }

    private fun toggleFavorite(track: MusicTrack) {
        val favorite = viewModel.toggleFavorite(track)
        renderFavorite(track)
        binding.immersiveFavorite.springPop(
            startScale = if (favorite) 0.66f else 0.82f,
            durationSeconds = if (favorite) 0.62f else 0.46f,
            bounce = if (favorite) 0.52f else 0.3f,
        )
        binding.immersiveFavorite.springHaptic()
        toast(if (favorite) R.string.player_added_favorite else R.string.player_removed_favorite)
    }

    /** 低频播放设置：定时关闭 + 倍速。 */
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
            onLyricOffsetChanged = {
                // 偏移变了要重新解析：已渲染的时间轴不会自己跟着变
                currentTrack?.let { loadLyric(it) }
            },
        ).show()
    }

    private fun formatSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    private fun toast(@StringRes resId: Int, @DrawableRes iconRes: Int = 0) {
        HudToast.show(this, getString(resId), iconRes, bottomMarginDp = 190)
    }

    private fun toastMessage(message: CharSequence) {
        HudToast.show(this, message, bottomMarginDp = 190)
    }

    override fun onDestroy() {
        lyricJob?.cancel()
        lyricJob = null
        backdropAnimator?.cancel()
        backdropAnimator = null
        super.onDestroy()
    }

    companion object {
        /** 与 PlayerController.setSleepTimer 的约定一致。 */
        private const val SLEEP_OFF = 0
        private const val SLEEP_TRACK_END = -1

        private const val BACKDROP_PERIOD_MS = 16_000L
        private const val BACKDROP_SCALE = 1.06f

        fun start(context: Context) {
            context.startActivity(Intent(context, ImmersivePlayerActivity::class.java))
        }
    }
}
