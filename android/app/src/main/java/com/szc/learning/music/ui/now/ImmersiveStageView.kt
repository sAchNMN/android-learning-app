package com.szc.learning.music.ui.now

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.szc.learning.music.ui.common.CoverColors
import com.szc.learning.music.ui.common.CoverPalette
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.music.ui.widget.withAlpha
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * 沉浸播放的「舞台」：封面主色光晕 + 缓慢上浮的微尘。
 *
 * 只画光，不画底 —— 深色画布和放大封面的柔光由页面自己的 View 负责，
 * 这样换歌时封面 crossfade 与光晕换色是两层独立的动画，不会互相打断。
 *
 * 粒子用系统帧回调推进，离开窗口 / 不可见 / 系统关闭动画时立刻停掉，
 * 不在后台空转。
 */
class ImmersiveStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val motePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var topColor = CoverPalette.Fallback.top
    private var bottomColor = CoverPalette.Fallback.bottom
    private var topShader: Shader? = null
    private var bottomShader: Shader? = null
    private var centerShader: Shader? = null

    private val random = Random(0x5EED)
    private val motes = List(MOTE_COUNT) {
        Mote(
            x = random.nextFloat(),
            y = random.nextFloat(),
            radius = context.dp(0.7f + random.nextFloat() * 1.7f),
            speed = 0.012f + random.nextFloat() * 0.028f,
            sway = 0.35f + random.nextFloat() * 1.1f,
            phase = random.nextFloat() * (PI.toFloat() * 2f),
            alpha = 0.08f + random.nextFloat() * 0.26f,
        )
    }

    private var running = false
    private var lastFrameMs = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrameMs).coerceIn(0L, 80L)) / 1000f
            lastFrameMs = now
            if (dt > 0f) {
                val t = now / 1000f
                motes.forEach { mote -> mote.advance(dt, t) }
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }

    fun setColors(colors: CoverColors) {
        if (colors.top == topColor && colors.bottom == bottomColor) return
        topColor = colors.top
        bottomColor = colors.bottom
        rebuildShaders()
        invalidate()
    }

    /** 页面不可见时停掉帧回调，回来再继续。 */
    fun pause() = stop()

    fun resume() = startIfVisible()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = max(w, h) * 0.82f
        topShader = RadialGradient(
            w * 0.84f,
            h * 0.14f,
            radius,
            withAlpha(topColor, 0.62f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        bottomShader = RadialGradient(
            w * 0.08f,
            h * 0.92f,
            radius * 0.92f,
            withAlpha(bottomColor, 0.72f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        centerShader = RadialGradient(
            w * 0.5f,
            h * 0.46f,
            max(w, h) * 0.52f,
            withAlpha(topColor, 0.22f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        glowPaint.style = Paint.Style.FILL
        listOf(topShader, centerShader, bottomShader).forEach { shader ->
            if (shader == null) return@forEach
            glowPaint.shader = shader
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        glowPaint.shader = null

        motePaint.style = Paint.Style.FILL
        val w = width.toFloat()
        val h = height.toFloat()
        motes.forEach { mote ->
            motePaint.color = Color.argb((mote.alpha * 255).toInt(), 255, 255, 255)
            canvas.drawCircle(mote.x * w, mote.y * h, mote.radius, motePaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { startIfVisible() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startIfVisible() else stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startIfVisible() else stop()
    }

    private fun startIfVisible() {
        if (running || !isAttachedToWindow || !isShown) return
        if (Motion.animationsDisabled(context)) {
            invalidate()
            return
        }
        running = true
        lastFrameMs = SystemClock.uptimeMillis()
        postOnAnimation(ticker)
    }

    private fun stop() {
        if (!running) return
        running = false
        removeCallbacks(ticker)
    }

    private class Mote(
        var x: Float,
        var y: Float,
        val radius: Float,
        val speed: Float,
        val sway: Float,
        val phase: Float,
        val alpha: Float,
    ) {
        fun advance(dt: Float, timeSeconds: Float) {
            y -= speed * dt
            if (y < -0.04f) {
                y = 1.04f
                x = (x + 0.37f) % 1f
            }
            x += sin(timeSeconds * sway + phase) * 0.012f * dt
            if (x < -0.03f) x = 1.03f
            if (x > 1.03f) x = -0.03f
        }
    }

    private companion object {
        const val MOTE_COUNT = 28
    }
}
