package com.szc.learning.music.ui.common

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * 兜底封面的 Drawable 版本。
 *
 * 和 [CoverArt.of] 用同一套哈希、色板、渐变坐标与色标，区别只在输出方式：
 * 不分配位图、不做软件光栅化，直接在硬件 Canvas 上画 4 层渐变。
 *
 * 为什么需要它：播放页每页同时绑封面和全屏背景，每次绑定都在主线程现画两张
 * 900~1024px 的渐变位图（合计 7~8MB），还会把列表行的小占位图挤出 6MB 缓存；
 * 上下滑切歌时这个开销正好压在封面解码、歌词切换最忙的那一帧上。
 *
 * Shader 在 [onBoundsChange] 里建一次，尺寸不变就不重建。
 */
class CoverArtDrawable(seed: String) : Drawable() {

    private val plan = CoverArt.planFor(seed)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var layers: List<Shader> = emptyList()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) {
            layers = emptyList()
            return
        }
        val second = plan.second
        layers = listOf(
            // 底色：两色过渡，方向按哈希翻
            if (plan.flip) {
                LinearGradient(
                    0f, 0f, w, h,
                    CoverArt.blend(plan.first, second, 0.88f), second,
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    w, 0f, 0f, h,
                    CoverArt.blend(plan.first, second, 0.88f), second,
                    Shader.TileMode.CLAMP,
                )
            },
            // 左上高光
            RadialGradient(
                if (plan.flip) w * 0.22f else w * 0.78f,
                h * 0.16f,
                max(w, h) * 0.85f,
                CoverArt.withAlpha(Color.WHITE, 0.24f),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            ),
            // 底部压暗
            LinearGradient(
                0f, h * 0.55f, 0f, h,
                Color.TRANSPARENT,
                CoverArt.withAlpha(Color.BLACK, 0.22f),
                Shader.TileMode.CLAMP,
            ),
            // 斜向玻璃反光
            LinearGradient(
                0f, h, w, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    CoverArt.withAlpha(Color.WHITE, 0.10f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0.34f, 0.5f, 0.66f),
                Shader.TileMode.CLAMP,
            ),
        )
    }

    override fun draw(canvas: Canvas) {
        if (layers.isEmpty()) return
        val bounds = bounds
        if (bounds.isEmpty) return
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        for (shader in layers) {
            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
