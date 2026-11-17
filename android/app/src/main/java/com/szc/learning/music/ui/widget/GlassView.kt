package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.szc.learning.R
import kotlin.math.min

/**
 * 液态玻璃容器。
 *
 * 为什么不用一个 shape drawable：XML 的 `<stroke>` 只能单色，
 * 而玻璃的真正质感来自**边缘上亮下暗的渐变描边**和**左上角那一块镜面高光**，
 * 这两样只有 Canvas 画得出来。所以这里四层叠上去：
 *
 * 1. 填充：顶部 62% 白 → 底部 26% 白（不是均匀半透明，均匀会显平）
 * 2. 镜面：左上角径向白光，模拟光源
 * 3. 顶部薄雾：上 55% 一抹垂直白光，制造"湿润"感
 * 4. 描边：沿对角线做 亮→几乎透明→微亮，这就是折射边
 * 5. 底部内阴影：让玻璃有厚度，不是一张纸
 *
 * 所有参数都能在 XML 里调，默认值就是标准玻璃。
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val depthPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val shape = Path()
    private val bounds = RectF()

    /** 圆角。超过高度一半会被夹住，避免画出自交的路径。 */
    var corner: Float = context.dp(20f)
        set(value) {
            field = value
            rebuild()
            invalidate()
        }

    var tint: Int = Color.WHITE
        set(value) {
            field = value
            rebuild()
            invalidate()
        }

    var topAlpha: Float = 0.62f
        set(value) { field = value; rebuild(); invalidate() }

    var bottomAlpha: Float = 0.26f
        set(value) { field = value; rebuild(); invalidate() }

    var rimAlpha: Float = 0.85f
        set(value) { field = value; rebuild(); invalidate() }

    var rimWidth: Float = context.dp(1.2f)
        set(value) { field = value; rebuild(); invalidate() }

    var sheenAlpha: Float = 0.34f
        set(value) { field = value; rebuild(); invalidate() }

    var blobAlpha: Float = 0.14f
        set(value) { field = value; rebuild(); invalidate() }

    var depthAlpha: Float = 0.05f
        set(value) { field = value; rebuild(); invalidate() }

    init {
        attrs?.let { readAttrs(it) }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        // 阴影靠 elevation 走系统，不自己画 —— 自己画的和系统阴影叠一起会脏
        clipToOutline = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.argb(16, 0, 0, 0)
            outlineSpotShadowColor = Color.argb(38, 0, 0, 0)
        }
    }

    private fun readAttrs(attrs: AttributeSet) {
        val a = context.obtainStyledAttributes(attrs, R.styleable.GlassView)
        val dark = a.getBoolean(R.styleable.GlassView_glassDark, false)
        corner = a.getDimension(R.styleable.GlassView_glassCorner, context.dp(20f))
        tint = a.getColor(R.styleable.GlassView_glassTint, if (dark) DARK_TINT else Color.WHITE)
        topAlpha = a.getFloat(R.styleable.GlassView_glassTopAlpha, if (dark) 0.20f else 0.62f)
        bottomAlpha = a.getFloat(R.styleable.GlassView_glassBottomAlpha, if (dark) 0.10f else 0.26f)
        rimAlpha = a.getFloat(R.styleable.GlassView_glassRimAlpha, if (dark) 0.42f else 0.85f)
        rimWidth = a.getDimension(R.styleable.GlassView_glassRimWidth, context.dp(1.2f))
        sheenAlpha = a.getFloat(R.styleable.GlassView_glassSheenAlpha, if (dark) 0.10f else 0.34f)
        blobAlpha = a.getFloat(R.styleable.GlassView_glassBlobAlpha, if (dark) 0.06f else 0.14f)
        depthAlpha = a.getFloat(R.styleable.GlassView_glassDepth, if (dark) 0.16f else 0.05f)
        elevation = a.getDimension(R.styleable.GlassView_glassElevation, context.dp(6f))
        a.recycle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val r = min(corner, min(w, h) / 2f)

        bounds.set(0f, 0f, w, h)
        shape.reset()
        shape.addRoundRect(bounds, r, r, Path.Direction.CW)

        // 1. 填充：三段而不是两段，中间偏亮一点，像光在玻璃里散开
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(
                withAlpha(tint, topAlpha),
                withAlpha(tint, (topAlpha + bottomAlpha) * 0.92f / 2f),
                withAlpha(tint, bottomAlpha),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 2. 边缘折射：左上亮 → 中间几乎透明 → 右下微亮
        rimPaint.strokeWidth = rimWidth
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                withAlpha(Color.WHITE, rimAlpha),
                withAlpha(Color.WHITE, rimAlpha * 0.06f),
                withAlpha(Color.WHITE, rimAlpha * 0.55f),
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )

        // 3. 顶部薄雾
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.55f,
            withAlpha(Color.WHITE, sheenAlpha),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )

        // 4. 左上镜面高光：圆比控件还大，只露出一小块弧，才像玻璃而不是灯泡
        blobPaint.shader = RadialGradient(
            w * 0.14f, -h * 0.10f, w * 0.92f,
            withAlpha(Color.WHITE, blobAlpha),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )

        // 5. 底部内阴影
        depthPaint.shader = LinearGradient(
            0f, h * 0.68f, 0f, h,
            Color.TRANSPARENT,
            withAlpha(Color.BLACK, depthAlpha),
            Shader.TileMode.CLAMP,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (shape.isEmpty) {
            super.dispatchDraw(canvas)
            return
        }
        drawGlass(canvas)
        onGlassDrawn(canvas)
        super.dispatchDraw(canvas)
    }

    /** 玻璃画完、子 View 之前。子类要往玻璃层上叠东西（比如 tab 选中胶囊）就覆写这个。 */
    protected open fun onGlassDrawn(canvas: Canvas) = Unit

    protected fun drawGlass(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawPath(shape, fillPaint)

        canvas.save()
        canvas.clipPath(shape)
        canvas.drawRect(0f, 0f, w, h * 0.56f, sheenPaint)
        canvas.drawRect(0f, 0f, w, h, blobPaint)
        canvas.drawRect(0f, h * 0.68f, w, h, depthPaint)
        canvas.restore()

        canvas.drawPath(shape, rimPaint)
    }

    private companion object {
        const val DARK_TINT = 0xFF101014.toInt()
    }
}
