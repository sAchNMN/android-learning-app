package com.szc.learning.music.ui.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.imageview.ShapeableImageView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.motion.installSpringPress
import kotlin.math.abs

/**
 * 播放行：嵌在 [BottomNavBar] 上半部分，和导航共用同一块玻璃，自己不画底。
 *
 * 一行内容：旋转封面 + 圆环进度 · 歌名/歌手 · 队列 · 播放/暂停 · 下一首；
 * 底部还有一条细线进度，可以直接按住拖动 seek。整行可点，展开成全屏播放页。
 *
 * 手势：左右滑切歌（左滑下一首、右滑上一首），点击展开。
 * 滑动用 [GestureDetector] 单独识别，不抢子按钮的点击 —— 子按钮自己消费触摸，
 * 事件根本不会冒到这里。
 */
class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onOpen: (() -> Unit)? = null
    var onPlayToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onQueue: (() -> Unit)? = null

    /** 松手时提交 seek。拖动过程中的画面由 [SeekLine] 自己预览，不必每帧都去 seek 播放器。 */
    var onSeekFinished: ((Float) -> Unit)? = null

    private val cover: ShapeableImageView
    private val ring: RingProgressView
    private val title: TextView
    private val artist: TextView
    private val playButton: ImageButton
    private val progressLine: SeekLine
    private var lastTrackKey: String? = null

    /** 封面自转。暂停时 pause() 停在当前角度，不归零。 */
    private var spin: ObjectAnimator? = null

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                start: MotionEvent?,
                end: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (start == null) return false
                val dx = end.x - start.x
                val dy = end.y - start.y
                // 必须是「横向为主」的滑动：竖着划是列表在滚，别切歌
                if (abs(dx) < context.dp(SWIPE_MIN_DP)) return false
                if (abs(dx) < abs(dy) * SWIPE_RATIO) return false
                if (abs(velocityX) < SWIPE_MIN_VELOCITY) return false
                if (dx < 0f) {
                    slideSwitch(direction = -1) { onNext?.invoke() }
                } else {
                    slideSwitch(direction = 1) { onPrevious?.invoke() }
                }
                return true
            }
        },
    )

    init {
        LayoutInflater.from(context).inflate(R.layout.view_mini_player, this, true)
        cover = findViewById(R.id.miniCover)
        ring = findViewById(R.id.miniRing)
        title = findViewById(R.id.miniTitle)
        artist = findViewById(R.id.miniArtist)
        playButton = findViewById(R.id.miniPlay)
        progressLine = findViewById(R.id.miniProgress)

        // 进度线可以拖：按下时露出圆点，松手提交 seek。
        // 拖动中的进度由 SeekLine 自己用 dragFraction 预览，不用每帧 seek 播放器。
        progressLine.knobRing = true
        progressLine.onSeekFinished = { fraction -> onSeekFinished?.invoke(fraction) }
        progressLine.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> progressLine.showKnob = true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> progressLine.showKnob = false
            }
            false
        }
        // 线只有 8dp 高，靠 TouchDelegate 把上下热区撑开
        progressLine.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateSeekTouchDelegate()
        }

        val ripple = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            foreground = context.getDrawable(ripple.resourceId)
        }

        playButton.setOnClickListener { onPlayToggle?.invoke() }
        findViewById<View>(R.id.miniQueue).setOnClickListener { onQueue?.invoke() }
        findViewById<View>(R.id.miniNext).setOnClickListener { onNext?.invoke() }
        setOnClickListener { onOpen?.invoke() }
        // 只管识别滑动，返回 false 让事件继续走正常的点击流程
        setOnTouchListener { _, event ->
            taps.onTouchEvent(event)
            false
        }
        listOf<View>(playButton, findViewById(R.id.miniQueue), findViewById(R.id.miniNext))
            .forEach { it.installSpringPress(0.86f) }
    }

    fun bind(
        track: MusicTrack?,
        isPlaying: Boolean,
        progress: Float,
    ) {
        if (track == null) {
            visibility = View.GONE
            updateSpin(false)
            return
        }
        if (visibility != View.VISIBLE) visibility = View.VISIBLE

        // 位置回调每 350ms 一次，只有进度真的变了。无条件 setText / setImageResource
        // 会让整块玻璃跟着失效重排；逐项比对后一次 tick 只剩进度线重画。
        if (title.text.toString() != track.title) title.text = track.title
        val artistText = track.artist.ifBlank {
            context.getString(R.string.player_unknown_artist)
        }
        if (artist.text.toString() != artistText) artist.text = artistText
        if (playButton.tag != isPlaying) {
            playButton.tag = isPlaying
            playButton.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            playButton.contentDescription =
                context.getString(if (isPlaying) R.string.player_pause else R.string.player_play)
        }
        if (!progressLine.isSeeking) {
            progressLine.progress = progress
            ring.progress = progress
        }
        updateSpin(isPlaying)

        // 封面只换一次，避免每 350ms 的位置回调都触发一次解码
        if (lastTrackKey != track.key) {
            lastTrackKey = track.key
            cover.bindArt(track, sizePx = 120)
        }
    }

    /**
     * 只推进进度。
     *
     * 进度单独一条流，350ms 一次；走完整 [bind] 虽然逐项比对了，但每次都要
     * 取 artist 字符串、查 tag，纯属浪费。这条路径只碰进度线与圆环。
     */
    fun bindProgress(progress: Float) {
        if (visibility != View.VISIBLE) return
        // 手指按着进度线时，别被 350ms 一次的位置回调顶回去
        if (progressLine.isSeeking) return
        progressLine.progress = progress
        ring.progress = progress
    }

    /**
     * 进度线只有 8dp 高，手指很难按准：把上下、左右各扩一点触摸区域。
     * 只改热区，视觉上的线和圆点都不变。
     */
    private fun updateSeekTouchDelegate() {
        if (progressLine.width == 0 || progressLine.height == 0) return
        val rect = Rect()
        progressLine.getHitRect(rect)
        rect.inset(
            -context.dp(SEEK_TOUCH_EXTRA_X_DP).toInt(),
            -context.dp(SEEK_TOUCH_EXTRA_DP).toInt(),
        )
        touchDelegate = TouchDelegate(rect, progressLine)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSeekTouchDelegate()
    }

    /** 播放时缓慢自转；暂停停在当前角度（不是归零），看起来才像"唱片停住了"。 */
    private fun updateSpin(playing: Boolean) {
        if (!playing) {
            spin?.takeIf { it.isRunning }?.pause()
            return
        }
        val animator = spin ?: ObjectAnimator.ofFloat(cover, View.ROTATION, 0f, 360f).apply {
            duration = SPIN_DURATION_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            start()
        }.also { spin = it }
        if (animator.isPaused) animator.resume()
    }

    /**
     * 切歌时给一点点位移和淡出，让"换了首"能被眼睛捕捉到。
     *
     * 只动这一条，不动页面：迷你条常驻底部，做太重的手势动画反而会挡住内容。
     * [direction] = -1 表示内容往左走（下一首），1 表示往右走（上一首）。
     */
    private fun slideSwitch(direction: Int, action: () -> Unit) {
        val shift = (width * SWIPE_OFFSET_RATIO * direction)
        animate().cancel()
        animate()
            .translationX(-shift)
            .alpha(0.55f)
            .setDuration(SWIPE_OUT_MS)
            .withEndAction {
                action()
                translationX = shift
                animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(SWIPE_IN_MS)
                    .start()
            }
            .start()
    }

    override fun onDetachedFromWindow() {
        // 列表/Activity 重建时不留一个跑着的无限动画（它会一直持有这个 View）
        spin?.cancel()
        spin = null
        animate().cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 封面转一圈的时间。20s 是"能看出在转，又不至于晃眼"的经验值。 */
        const val SPIN_DURATION_MS = 20_000L
        const val SWIPE_MIN_DP = 48f
        const val SWIPE_RATIO = 1.5f
        const val SWIPE_MIN_VELOCITY = 350f
        const val SWIPE_OFFSET_RATIO = 0.06f
        const val SWIPE_OUT_MS = 90L
        const val SWIPE_IN_MS = 140L

        /** 进度线触摸热区在上下 / 左右方向额外撑开的距离（dp）。 */
        const val SEEK_TOUCH_EXTRA_DP = 8f
        const val SEEK_TOUCH_EXTRA_X_DP = 4f
    }
}
