package com.szc.learning.music.ui.now

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ActivityNowPlayingBinding
import com.szc.learning.databinding.DialogQueueBinding
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.track.TrackAdapter
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 正在播放。
 *
 * 三个不显眼但决定质感的东西：
 * 1. 背景是**同一张封面放大 4 倍再高斯模糊**，再压一层黑 —— 所以整页颜色随歌曲走
 * 2. 主键是页面上唯一实心白的元素，视线自然落在它上面
 * 3. 向下拖拽可以收起，拖到 15% 高度松手就退出，和 iOS 一致
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNowPlayingBinding
    private val viewModel: MusicViewModel by viewModels()

    private var durationMs = 0L
    private var lastTrackKey: String? = null
    private var downY = 0f
    private var downX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityNowPlayingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        sizeCover()
        setupControls()
        setupDragToDismiss()
        observe()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val base = (8 * resources.displayMetrics.density).toInt()
            // 顶栏只让出状态栏，底栏让出导航栏；中间的舞台不参与，保持居中
            binding.topBar.updatePadding(top = bars.top)
            binding.volumeRow.updatePadding(bottom = bars.bottom + base)
            insets
        }
    }

    /**
     * 封面按屏宽定尺寸，保证正方形且左右留白一致。
     * 写死 dp 会在小屏上超出、大屏上缩成一小块，所以按可用宽度算，再封顶 340dp。
     */
    private fun sizeCover() {
        val density = resources.displayMetrics.density
        val available = resources.displayMetrics.widthPixels - (48 * density).toInt()
        val size = minOf(available, (340 * density).toInt())
        binding.nowCover.layoutParams = binding.nowCover.layoutParams.apply {
            width = size
            height = size
        }
    }

    private fun setupControls() {
        binding.btnCollapse.setOnClickListener { closeWithTransition() }

        binding.btnPlay.setOnClickListener { viewModel.toggle() }
        binding.btnNext.setOnClickListener { viewModel.next() }
        binding.btnPrev.setOnClickListener { viewModel.previous() }
        binding.btnShuffle.setOnClickListener { viewModel.toggleShuffle() }
        binding.btnRepeat.setOnClickListener { viewModel.cycleRepeat() }
        binding.btnQueue.setOnClickListener { showQueue() }

        binding.nowSeek.showKnob = true
        binding.nowSeek.onDark = true
        binding.nowSeek.onSeek = { fraction ->
            binding.timePosition.text = formatDuration((durationMs * fraction).toLong())
        }
        binding.nowSeek.onSeekFinished = { fraction -> viewModel.seekToFraction(fraction) }

        binding.volumeLine.showKnob = true
        binding.volumeLine.onDark = true
        binding.volumeLine.progress = INITIAL_VOLUME
        binding.volumeLine.onSeek = { fraction ->
            binding.volumeLine.progress = fraction
            viewModel.setVolume(fraction)
        }
        viewModel.setVolume(INITIAL_VOLUME)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: PlayerUiState) {
        val track = state.track
        if (track == null) {
            // 队列被清空（比如后台服务被系统杀掉），这一页就没有意义了
            finish()
            return
        }

        if (lastTrackKey != track.key) {
            lastTrackKey = track.key
            binding.nowTitle.text = track.title
            binding.nowArtist.text = track.artist.ifBlank {
                getString(R.string.player_unknown_artist)
            }
            binding.nowCover.bindArt(track, sizePx = 800)
            renderBackdrop(track)
        }

        durationMs = state.durationMs
        binding.timePosition.text = formatDuration(state.positionMs)
        binding.timeDuration.text = formatDuration(state.durationMs)
        binding.nowSeek.progress = state.progress

        binding.btnPlay.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
        )
        binding.btnPlay.contentDescription =
            getString(if (state.isPlaying) R.string.player_pause else R.string.player_play)

        val active = Color.WHITE
        val idle = Color.argb(120, 255, 255, 255)
        binding.btnShuffle.imageTintList =
            ColorStateList.valueOf(if (state.shuffle) active else idle)
        binding.btnRepeat.imageTintList =
            ColorStateList.valueOf(if (state.repeatMode == 0) idle else active)
    }

    /**
     * 背景：封面原图铺满 + 高斯模糊 + 压暗。
     * 31 以下没有 RenderEffect，退化成本身就不清晰的放大图，视觉上也能接受。
     */
    private fun renderBackdrop(track: MusicTrack) {
        val fallback = BitmapDrawable(resources, CoverArt.of(track.key, 320))
        val art = track.artUri
        if (art.isNullOrBlank()) {
            binding.backdrop.setImageDrawable(fallback)
        } else {
            binding.backdrop.load(art) {
                placeholder(fallback)
                error(fallback)
                fallback(fallback)
                crossfade(220)
            }
        }
        binding.backdrop.alpha = BACKDROP_ALPHA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.backdrop.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    60f, 60f, Shader.TileMode.CLAMP,
                ),
            )
        }
    }

    private fun showQueue() {
        val dialog = BottomSheetDialog(this)
        val sheet = DialogQueueBinding.inflate(layoutInflater)
        val queue = viewModel.queue.value

        val adapter = TrackAdapter { index -> viewModel.playAt(index) }
        sheet.queueList.layoutManager = LinearLayoutManager(this)
        sheet.queueList.adapter = adapter
        adapter.submitList(queue)

        dialog.setContentView(sheet.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    /** 向下拖：跟手位移 + 透明度衰减，松手时按位移决定关闭还是回弹。 */
    private fun setupDragToDismiss() {
        binding.nowRoot.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    downX = event.rawX
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    val dx = abs(event.rawX - downX)
                    if (dy > 0f && dx < dy) {
                        view.translationY = dy
                        view.alpha = (1f - dy / (view.height * 0.75f)).coerceIn(0.35f, 1f)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (view.translationY > view.height * DISMISS_FRACTION) {
                        closeWithTransition()
                    } else {
                        view.animate().translationY(0f).alpha(1f).setDuration(180).start()
                    }
                    false
                }

                else -> false
            }
        }
    }

    private fun closeWithTransition() {
        finish()
    }

    private companion object {
        const val BACKDROP_ALPHA = 0.92f
        const val INITIAL_VOLUME = 0.8f
        const val DISMISS_FRACTION = 0.15f
    }
}
