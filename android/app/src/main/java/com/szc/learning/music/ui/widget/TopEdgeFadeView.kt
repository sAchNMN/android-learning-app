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
 * 状态栏下面的柔和渐隐。
 *
 * 页面是 edge-to-edge 的：列表可以滚到状态栏下面（内容"入侵"通知栏），
 * 但滚上去的文字不能和时间/信号图标糊成一团。这里不画实色蒙版，
 * 只铺一条从 canvas 色到透明的渐变，内容仍然透得出来。
 *
 * 播放器页是整屏深色，会把它隐藏。
 */
class TopEdgeFadeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastColor = Int.MIN_VALUE

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val canvasColor = context.themeColor(R.attr.auCanvas)
        if (canvasColor != lastColor) {
            lastColor = canvasColor
            paint.shader = LinearGradient(
                0f,
                0f,
                0f,
                height.toFloat().coerceAtLeast(1f),
                intArrayOf(
                    withAlpha(canvasColor, 0.88f),
                    withAlpha(canvasColor, 0.55f),
                    withAlpha(canvasColor, 0f),
                ),
                floatArrayOf(0f, 0.38f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onDetachedFromWindow() {
        paint.shader = null
        super.onDetachedFromWindow()
    }
}