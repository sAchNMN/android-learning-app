package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor

/**
 * 悬浮导航后面的渐隐，和顶部的 [TopEdgeFadeView] 对称。
 *
 * 导航玻璃是半透明的，列表文字滚到它后面会透出来，压在播放行的歌名上很难读。
 * 这里不动玻璃，而是让内容在靠近导航时自己淡出：渐变从导航上沿之上
 * [FADE_LEAD_DP] 开始，到导航处已接近页面底色。
 *
 * 高度由 MainActivity 跟随 dock 设置（有无播放行时 dock 高度不同）。
 * 播放 tab 是整屏深色，会把它隐藏。
 */
class BottomEdgeFadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastColor = Int.MIN_VALUE
    private var lastHeight = -1

    /** 导航玻璃本体的高度（不含底部留白），渐变在它之上 [FADE_LEAD_DP] 处开始。 */
    var glassHeightPx: Int = 0
        set(value) {
            if (field == value) return
            field = value
            lastHeight = -1
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val canvasColor = context.themeColor(R.attr.auCanvas)
        if (canvasColor != lastColor || height != lastHeight) {
            lastColor = canvasColor
            lastHeight = height
            val h = height.toFloat().coerceAtLeast(1f)
            val lead = FADE_LEAD_DP * resources.displayMetrics.density
            // 导航上沿的位置（占总高的比例）：渐变到这里已经盖住 80% 以上
            val edge = (1f - ((glassHeightPx + lead).coerceAtMost(h) - lead)
                .coerceAtLeast(0f) / h).coerceIn(0.05f, 0.95f)
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    withAlpha(canvasColor, 0f),
                    withAlpha(canvasColor, 0.82f),
                    withAlpha(canvasColor, 0.94f),
                ),
                floatArrayOf(0f, edge, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        paint.shader = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val FADE_LEAD_DP = 36f
    }
}
