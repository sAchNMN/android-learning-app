package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.szc.learning.R
import kotlin.math.max

/**
 * 极光底。
 *
 * 四个大范围径向色斑叠在近白底上，构成 iOS 那种"柔光台面"。
 * 关键是**不透明**也不能是纯渐变线性 —— 线性渐变一眼假，径向色斑才有弥散的体积感。
 *
 * 性能：整张图在 onSizeChanged 时按 1/3 分辨率预渲染进 Bitmap，
 * 之后每帧只是 drawBitmap 拉伸。三分辨率再放大本身就是一次柔化，
 * 比实时画四个 RadiaGradient 省一个数量级的 CPU，滚动时不会掉帧。
 */
class AuroraBackground @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var mesh: Bitmap? = null
    private val dst = RectF()

    private var intensity: Float = 1f

    init {
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.AuroraBackground)
            intensity = a.getFloat(R.styleable.AuroraBackground_auroraIntensity, 1f)
            a.recycle()
        }
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        renderMesh(w, h)
    }

    private fun renderMesh(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val bw = max(1, w / 3)
        val bh = max(1, h / 3)
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.canvas))

        val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val sw = bw.toFloat()
        val sh = bh.toFloat()
        // 半径一律用宽度算，保证竖屏/横屏色斑形状一致
        blobs().forEach { blob ->
            val cx = blob.fx * sw
            val cy = blob.fy * sh
            val radius = blob.fr * sw
            blobPaint.shader = RadialGradient(
                cx, cy, radius,
                withAlpha(ContextCompat.getColor(context, blob.colorRes), blob.alpha * intensity),
                android.graphics.Color.TRANSPARENT,
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, blobPaint)
        }
        blobPaint.shader = null

        mesh?.recycle()
        mesh = bitmap
        invalidate()
    }

    private fun blobs(): List<Blob> = listOf(
        Blob(R.color.accent_pink, 0.12f, 0.06f, 0.95f, 0.34f),
        Blob(R.color.accent_violet, 0.98f, 0.20f, 1.05f, 0.30f),
        Blob(R.color.accent_cyan, 0.06f, 0.78f, 1.00f, 0.26f),
        Blob(R.color.accent_amber, 0.92f, 0.96f, 0.70f, 0.16f),
    )

    override fun onDraw(canvas: Canvas) {
        val bitmap = mesh
        if (bitmap == null) {
            canvas.drawColor(ContextCompat.getColor(context, R.color.canvas))
        } else {
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
    }

    override fun onDetachedFromWindow() {
        mesh?.recycle()
        mesh = null
        super.onDetachedFromWindow()
    }

    private data class Blob(
        val colorRes: Int,
        val fx: Float,
        val fy: Float,
        val fr: Float,
        val alpha: Float,
    )
}
