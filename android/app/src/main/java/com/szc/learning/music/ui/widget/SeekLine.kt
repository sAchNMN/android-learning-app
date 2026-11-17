package com.szc.learning.music.ui.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.szc.learning.R

/**
 * 播放进度条。一根能拖的线。
 *
 * 自绘而不是用 SeekBar：SeekBar 的 thumb 是 Drawable，做不出
 * 「进度线是渐变、拖到哪 thumb 就在哪、并且 thumb 带玻璃质感」这一套；
 * 而且系统 SeekBar 的触摸区域只有线那么高，手指根本按不准。
 * 这里控件高度就是触摸高度（28dp），线只有 4dp，手感和观感分开。
 */
class SeekLine @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** 迷你播放器里只要线，不要圆点。 */
    var showKnob: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /** 深色底（正在播放页）上用白色轨道，浅色底上用淡墨色轨道。 */
    var onDark: Boolean = false
        set(value) {
            field = value
            applyTrackColor()
            invalidate()
        }

    var onSeek: ((Float) -> Unit)? = null
    var onSeekFinished: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.white)
    }
    private val knobShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(android.graphics.Color.BLACK, 0.22f)
    }

    init {
        applyTrackColor()
    }

    private fun applyTrackColor() {
        trackPaint.color = if (onDark) {
            withAlpha(android.graphics.Color.WHITE, 0.20f)
        } else {
            withAlpha(ContextCompat.getColor(context, R.color.ink), 0.14f)
        }
    }

    private val lineRect = RectF()
    private var dragging = false
    private var dragFraction = 0f

    private val thickness: Float
        get() = if (showKnob) context.dp(4f) else context.dp(2f)

    private val knobRadius: Float
        get() = context.dp(6.5f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        progressPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                ContextCompat.getColor(context, R.color.progress_start),
                ContextCompat.getColor(context, R.color.progress_mid),
                ContextCompat.getColor(context, R.color.progress_end),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cy = height / 2f
        val half = thickness / 2f
        val fraction = if (dragging) dragFraction else progress

        lineRect.set(0f, cy - half, w, cy + half)
        canvas.drawRoundRect(lineRect, half, half, trackPaint)

        if (fraction > 0f) {
            lineRect.set(0f, cy - half, w * fraction, cy + half)
            canvas.drawRoundRect(lineRect, half, half, progressPaint)
        }

        if (showKnob) {
            val cx = (w * fraction).coerceIn(knobRadius, w - knobRadius)
            canvas.drawCircle(cx, cy + context.dp(1f), knobRadius, knobShadow)
            canvas.drawCircle(cx, cy, knobRadius, knobPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                dragFraction = fractionOf(event.x)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    dragFraction = fractionOf(event.x)
                    onSeek?.invoke(dragFraction)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragFraction = fractionOf(event.x)
                    dragging = false
                    onSeekFinished?.invoke(dragFraction)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fractionOf(x: Float): Float =
        if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)
}
