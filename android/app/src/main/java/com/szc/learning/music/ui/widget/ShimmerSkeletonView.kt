package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.szc.learning.R
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.ui.theme.themeColor
import kotlin.math.min

/**
 * 骨架屏 + 流光（shimmer）。
 *
 * 不是给加载中的图片贴一块灰底，而是把"这一页即将出现的结构"先画出来：
 * 标题、搜索条、主卡、快捷入口、分区标题、歌曲行……尺寸和真内容对齐，
 * 数据到了以后不会跳版。
 *
 * 流光是一条高光带按 1.5s 的循环从左扫到右，高光颜色跟着亮暗模式走；
 * 系统关掉动画时退化成静态骨架。
 */
class ShimmerSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Style { HOME, LIBRARY, RECOMMEND }

    var style: Style = Style.HOME
        set(value) {
            if (field == value) return
            field = value
            rebuild()
            invalidate()
        }

    private data class Block(val rect: RectF, val radius: Float, val path: Path)

    private val blocks = mutableListOf<Block>()
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val darkCanvas: Boolean by lazy {
        val color = context.themeColor(R.attr.auCanvas)
        val luminance = 0.2126f * android.graphics.Color.red(color) +
            0.7152f * android.graphics.Color.green(color) +
            0.0722f * android.graphics.Color.blue(color)
        luminance < 128f
    }

    private var phase = 0f
    private var animating = false
    private var startedAt = 0L
    private val shimmerLoop = object : Runnable {
        override fun run() {
            if (!animating || !isAttachedToWindow) return
            val elapsed = (SystemClock.uptimeMillis() - startedAt) % SHIMMER_MS
            phase = elapsed / SHIMMER_MS.toFloat()
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        contentDescription = context.getString(R.string.loading)
        basePaint.color = context.themeColor(R.attr.auSurfacePressed)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // padding 变化（状态栏 inset 晚到）时尺寸不一定变，但骨架要重排
        if (changed) rebuild()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blocks.isEmpty()) return
        for (block in blocks) {
            canvas.drawPath(block.path, basePaint)
        }
        if (phase <= 0f) return

        // 一条覆盖全屏的高光带，按 block 裁剪后分别绘制：
        // 这样所有方块共享同一次扫光，不会各扫各的。
        val w = width.toFloat().coerceAtLeast(1f)
        val band = w * 0.34f
        val center = -band + phase * (w + band * 2f)
        shinePaint.shader = LinearGradient(
            center - band,
            0f,
            center + band,
            0f,
            intArrayOf(
                withAlpha(0xFFFFFFFF.toInt(), 0f),
                if (darkCanvas) withAlpha(0xFFFFFFFF.toInt(), 0.10f)
                else withAlpha(0xFFFFFFFF.toInt(), 0.62f),
                withAlpha(0xFFFFFFFF.toInt(), 0f),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        for (block in blocks) {
            canvas.drawPath(block.path, shinePaint)
        }
        shinePaint.shader = null
    }

    // ---------------------------------------------------------------- 结构

    private fun rebuild() {
        blocks.clear()
        if (width == 0 || height == 0) return
        when (style) {
            Style.HOME -> buildHome()
            Style.LIBRARY -> buildLibrary()
            Style.RECOMMEND -> buildRecommend()
        }
    }

    private fun buildHome() {
        val x = paddingLeft + dp(20f)
        val right = width - paddingRight - dp(20f)
        val content = (right - x).coerceAtLeast(dp(40f))
        var y = paddingTop + dp(4f)

        add(x, y, x + dp(122f), y + dp(28f), dp(9f))
        add(right - dp(86f), y + dp(2f), right - dp(48f), y + dp(40f), dp(19f))
        add(right - dp(38f), y + dp(2f), right, y + dp(40f), dp(19f))
        y += dp(50f)

        add(x, y, right, y + dp(44f), dp(22f))
        y += dp(58f)

        add(x, y, right, y + dp(176f), dp(24f))
        y += dp(194f)

        val slot = content / 5f
        repeat(5) { index ->
            val centerX = x + slot * index + slot / 2f
            val top = y
            add(
                centerX - dp(23f),
                top,
                centerX + dp(23f),
                top + dp(46f),
                dp(23f),
            )
            add(
                centerX - dp(17f),
                top + dp(56f),
                centerX + dp(17f),
                top + dp(68f),
                dp(5f),
            )
        }
        y += dp(92f)

        add(x, y, x + dp(120f), y + dp(18f), dp(6f))
        y += dp(30f)
        val cardWidth = dp(150f)
        val gap = dp(14f)
        repeat(3) { index ->
            val left = x + index * (cardWidth + gap)
            if (left < right) {
                add(left, y, min(left + cardWidth, right), y + dp(154f), dp(18f))
                add(left, y + dp(166f), min(left + dp(104f), right), y + dp(180f), dp(5f))
            }
        }
        y += dp(204f)

        add(x, y, x + dp(120f), y + dp(18f), dp(6f))
        y += dp(30f)
        repeat(5) {
            add(x, y, x + dp(46f), y + dp(46f), dp(12f))
            add(x + dp(58f), y + dp(8f), min(x + dp(250f), right), y + dp(22f), dp(5f))
            add(x + dp(58f), y + dp(30f), min(x + dp(180f), right), y + dp(41f), dp(4f))
            y += dp(62f)
        }
    }

    private fun buildLibrary() {
        val x = paddingLeft + dp(20f)
        val right = width - paddingRight - dp(20f)
        var y = paddingTop + dp(12f)
        add(x, y, right, y + dp(44f), dp(22f))
        y += dp(60f)
        add(x, y, x + dp(140f), y + dp(14f), dp(5f))
        add(right - dp(72f), y - dp(4f), right, y + dp(28f), dp(16f))
        y += dp(44f)
        val tab = (right - x) / 4f
        repeat(4) { index ->
            add(x + tab * index + dp(10f), y, x + tab * index + dp(48f), y + dp(13f), dp(5f))
        }
        y += dp(34f)
        repeat(8) {
            add(x, y, x + dp(46f), y + dp(46f), dp(12f))
            add(x + dp(58f), y + dp(8f), min(x + dp(260f), right), y + dp(22f), dp(5f))
            add(x + dp(58f), y + dp(30f), min(x + dp(190f), right), y + dp(41f), dp(4f))
            add(right - dp(30f), y + dp(14f), right, y + dp(27f), dp(5f))
            y += dp(62f)
        }
    }

    private fun buildRecommend() {
        val x = paddingLeft + dp(20f)
        val right = width - paddingRight - dp(20f)
        val content = (right - x).coerceAtLeast(dp(40f))
        var y = paddingTop + dp(4f)
        add(x, y, x + dp(100f), y + dp(28f), dp(9f))
        add(right - dp(38f), y + dp(2f), right, y + dp(40f), dp(19f))
        y += dp(48f)
        add(x, y, x + dp(168f), y + dp(12f), dp(5f))
        y += dp(28f)
        add(x, y, right, y + dp(150f), dp(24f))
        y += dp(172f)
        add(x, y, x + dp(120f), y + dp(18f), dp(6f))
        y += dp(30f)
        val cardWidth = dp(150f)
        repeat(3) { index ->
            val left = x + index * (cardWidth + dp(14f))
            if (left < right) {
                add(left, y, min(left + cardWidth, right), y + dp(154f), dp(18f))
                add(left, y + dp(166f), min(left + dp(104f), right), y + dp(180f), dp(5f))
            }
        }
        y += dp(204f)
        add(x, y, x + dp(120f), y + dp(18f), dp(6f))
        y += dp(30f)
        repeat(4) { index ->
            add(x + index * dp(84f), y, x + index * dp(84f) + dp(64f), y + dp(64f), dp(32f))
        }
        y += dp(84f)
        add(x, y, x + dp(120f), y + dp(18f), dp(6f))
        y += dp(30f)
        repeat(3) {
            add(x, y, right, y + dp(72f), dp(18f))
            y += dp(84f)
        }
        if (content < 0f) return
    }

    private fun add(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        if (right <= left + 1f || bottom <= top + 1f) return
        if (top > height || left > width) return
        val rect = RectF(left, top, right, bottom)
        val p = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        blocks += Block(rect, radius, p)
    }

    private fun dp(value: Float): Float = context.dp(value)

    // ---------------------------------------------------------------- 动画

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startShimmer() else stopShimmer()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startShimmer()
    }

    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }

    private fun startShimmer() {
        if (animating) return
        if (Motion.animationsDisabled(context)) {
            phase = 0f
            return
        }
        animating = true
        startedAt = SystemClock.uptimeMillis()
        postOnAnimation(shimmerLoop)
    }

    private fun stopShimmer() {
        animating = false
        removeCallbacks(shimmerLoop)
        phase = 0f
        invalidate()
    }

    private companion object {
        const val SHIMMER_MS = 1500L
    }
}