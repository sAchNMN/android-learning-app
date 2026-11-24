package com.szc.learning.music.ui.widget

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 连续曲率圆角（Apple 的 squircle，不是普通 roundRect）。
 *
 * 普通圆角在直边和圆弧交界处曲率突变，放大看有一条"折痕"；
 * 用超椭圆 `|x/a|^n + |y/b|^n = 1`（n ≈ 4）过渡，曲率是连续的。
 * 悬浮玻璃条、选中胶囊、底部 dock 都用它。
 *
 * n = 2 时退化成标准圆形圆角，胶囊（半高半径）仍走圆。
 */
object Squircle {

    /**
     * 把矩形 [rect] 的四角写进 [out]。`exponent` 越大角越"方"，
     * 4.2 接近 iOS 的 continuous corner；胶囊请传 2。
     */
    fun path(
        rect: RectF,
        radius: Float,
        out: Path = Path(),
        exponent: Float = 4.2f,
    ): Path {
        out.reset()
        val r = min(radius, min(rect.width(), rect.height()) / 2f)
        if (r <= 0.5f) {
            out.addRect(rect, Path.Direction.CW)
            return out
        }
        val power = 2f / exponent.coerceAtLeast(2f)
        val steps = 10

        fun corner(
            cx: Float,
            cy: Float,
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
        ) {
            // 从"贴着前一条边"的端点走到"贴着后一条边"的端点
            for (i in 1..steps) {
                val angle = (PI / 2.0) * (i.toDouble() / steps)
                val c = cos(angle).toFloat().pow(power)
                val s = sin(angle).toFloat().pow(power)
                val px = cx + r * (startX * c + endX * s)
                val py = cy + r * (startY * c + endY * s)
                out.lineTo(px, py)
            }
        }

        out.moveTo(rect.left + r, rect.top)
        out.lineTo(rect.right - r, rect.top)
        // 右上：从上边走到右边
        corner(rect.right - r, rect.top + r, 0f, -1f, 1f, 0f)
        out.lineTo(rect.right, rect.bottom - r)
        // 右下：从右边走到下边
        corner(rect.right - r, rect.bottom - r, 1f, 0f, 0f, 1f)
        out.lineTo(rect.left + r, rect.bottom)
        // 左下：从下边走到左边
        corner(rect.left + r, rect.bottom - r, 0f, 1f, -1f, 0f)
        out.lineTo(rect.left, rect.top + r)
        // 左上：从左边走到上边
        corner(rect.left + r, rect.top + r, -1f, 0f, 0f, -1f)
        out.close()
        return out
    }
}