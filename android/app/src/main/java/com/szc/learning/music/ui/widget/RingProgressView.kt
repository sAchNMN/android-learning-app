package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor
import kotlin.math.abs
import kotlin.math.min

/**
 * 细圆环进度：套在迷你播放器的封面外面。
 *
 * 迷你条底部已经有一条 SeekLine 了，为什么还要环：那条线是行与 tab 行之间的分隔，
 * 细得几乎看不见；封面外面一圈进度是「一眼就知道播到哪」的位置，也不用占额外高度。
 *
 * 绘制约定：
 * - Paint 在构造时建好，`onDraw` 里不 new 任何对象（每个 350ms 都会重画）
 * - 进度变化小于 0.5% 直接跳过 invalidate —— 圆环一圈才 300dp，0.5% 连一个像素都不到
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var progress: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            // 首尾两帧必须画：取整后 0 和 1 是"停在起点 / 已经走完"的可见状态
            if (abs(next - field) < MIN_STEP && next > 0f && next < 1f) return
            field = next
            invalidate()
        }

    private val stroke = context.dp(STROKE_DP)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = withAlpha(context.themeColor(R.attr.auInk), 0.10f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = context.themeColor(R.attr.auAccent)
    }

    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val half = size / 2f
        val radius = half - stroke / 2f
        oval.set(half - radius, half - radius, half + radius, half + radius)

        canvas.drawCircle(half, half, radius, trackPaint)
        if (progress > 0f) {
            val sweep = 360f * progress
            canvas.drawArc(oval, START_ANGLE, sweep, false, progressPaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha * 255f).toInt() and 0xFF) shl 24)

    private companion object {
        const val STROKE_DP = 2f

        /** 12 点方向起画。 */
        const val START_ANGLE = -90f

        /** 小于这个比例的重绘没有意义。 */
        const val MIN_STEP = 0.005f
    }
}
