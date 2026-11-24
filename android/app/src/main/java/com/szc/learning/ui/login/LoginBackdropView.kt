package com.szc.learning.ui.login

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor

/**
 * 登录页背景：深色画布 + 三团极光光晕。
 *
 * 光晕颜色取当前主题的色斑（accent / blobD / blobB），所以换主题仍然有区分度；
 * 画布本身固定深色 —— 登录页是品牌首屏，不跟随明暗切换，
 * 否则浅色模式下白底 + 白字会直接不可读。
 */
class LoginBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseColor = context.themeColor(R.attr.auDarkCanvas)
    private val accent = context.themeColor(R.attr.auAccent)
    private val glowCyan = context.themeColor(R.attr.auBlobD)
    private val glowViolet = context.themeColor(R.attr.auBlobB)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(baseColor)
        drawGlow(canvas, accent, width * 0.86f, height * 0.12f, width * 0.78f, 104)
        drawGlow(canvas, glowCyan, width * 0.04f, height * 0.68f, width * 0.72f, 76)
        drawGlow(canvas, glowViolet, width * 0.58f, height * 0.40f, width * 0.58f, 52)

        // 底部压暗：让「仅本地播放」和底部说明文字始终有足够对比度
        paint.shader = RadialGradient(
            width * 0.5f,
            height * 1.06f,
            height * 0.8f,
            intArrayOf(Color.argb(140, 0, 0, 0), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height * 0.3f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun drawGlow(
        canvas: Canvas,
        color: Int,
        cx: Float,
        cy: Float,
        radius: Float,
        alpha: Int,
    ) {
        if (radius <= 0f) return
        paint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, alpha), Color.TRANSPARENT),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
