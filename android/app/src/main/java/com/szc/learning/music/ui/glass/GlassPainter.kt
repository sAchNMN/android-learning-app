package com.szc.learning.music.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.szc.learning.R
import com.szc.learning.music.ui.widget.Squircle
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.music.ui.widget.withAlpha
import com.szc.learning.ui.theme.themeColor

/**
 * 液态玻璃的绘制引擎，被迷你播放器和底部导航共用。
 *
 * 结构上照 iOS 26 / liquid-glass-react 的思路拆成五层：
 * 1. **折射底色**：把背后内容录进 GPU 纹理，模糊之后铺进来；液态模式下它的边缘
 *    还会被 AGSL shader 朝中心"掰"一下，做真正的折射和色散（见 [GlassBackdrop]）；
 * 2. **染色**：半透明主题色。`frost` 越大越厚、越不透明，这是"磨砂程度"；
 * 3. **竖向柔光**：顶部亮、底部略压暗，玻璃才有厚度而不是一张半透明纸；
 * 4. **边缘折射**：左右两侧收暗 + 顶部一道内高光，模拟光在玻璃边缘拐弯；
 * 5. **描边**：外圈发丝线 + 一圈随高度变化的镜面高光，液态下再叠一点极轻的色散。
 *
 * 绘制过程不分配对象：路径、Paint、渐变缓冲都是复用的，只有渐变对象每帧新建
 * （Android 的 Shader 不可变，这是最便宜的做法）。
 */
class GlassPainter(private val context: Context) {

    /** 当前外观。由 [GlassSurfaceView] 在设置变化时写入。 */
    var spec: GlassSpec = GlassSpec.DEFAULT
        private set

    /** 深色玻璃：夜间主题，或播放器 tab 这种压着深色封面的场景。 */
    var dark: Boolean = false
        private set

    var cornerRadius: Float = context.dp(20f)
        private set

    /** 触摸镜面：按下时光斑跟着手指走，松手淡出。回调"液态"手感的那一下。 */
    var lensX: Float = -1f
    var lensY: Float = -1f
    var lensStrength: Float = 0f

    private var surfaceColor = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fringePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val panelPath = Path()
    private val edgePath = Path()
    private val rimPath = Path()
    private val rect = RectF()
    private val edgeRect = RectF()
    private val rimRect = RectF()

    fun configure(spec: GlassSpec, dark: Boolean, cornerRadius: Float) {
        this.spec = spec.clamped()
        this.dark = dark
        this.cornerRadius = cornerRadius
        surfaceColor = context.themeColor(R.attr.auSurface)
    }

    /**
     * 画整块玻璃。坐标系就是控件自己的 0..width / 0..height。
     * [backdrop] 是面板正后方的折射底色（GPU 纹理或老的模糊小图）；为 null 时
     * 说明这一帧还没录到 / 设备不支持，玻璃退化成纯半透明染色。
     */
    fun draw(canvas: Canvas, width: Int, height: Int, backdrop: GlassBackdrop?) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val frost = spec.frost.coerceIn(0f, 1f)
        val liquid = spec.mode == GlassMode.LIQUID
        val hasBackdrop = backdrop != null
        buildPaths(w, h)

        canvas.save()
        canvas.clipPath(panelPath)

        // 1. 折射底色：GPU 版已经在 shader 里提过饱和度，老的小图版在这里补
        if (backdrop != null) {
            (backdrop as? BitmapBackdrop)?.setSaturation(1.18f + 0.5f * frost)
            backdrop.drawInto(canvas, w, h)
        }

        // 2. 染色：frost 越大越"厚"。
        //    有折射底色时染色可以薄很多（内容已经被折射/模糊处理过，不会串字）；
        //    没有底色（API 31 以下且非高斯模式）就得靠厚一点的染色压住背后的文字。
        val baseAlpha = when {
            liquid && hasBackdrop -> 0.26f + 0.34f * frost
            liquid -> 0.36f + 0.42f * frost
            else -> 0.08f + 0.34f * frost
        }
        // 深色下不能用更深的底色：玻璃会变成背景上的一个"洞"。
        // 真实世界的深色玻璃是一层薄薄的白雾，比周围略亮。
        fillPaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.06f + 0.14f * frost)
        } else {
            withAlpha(surfaceColor, baseAlpha)
        }
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        canvas.restore()
    }

    /**
     * 玻璃本体：柔光、边缘、斜向反光、触摸镜面和描边。画在折射底色之上、内容之下，
     * 由 [GlassSurfaceView] 在 dispatchDraw 里紧接着底色子 View 调用 ——
     * 底色必须挂着 RenderEffect 单独作为子 View 画，顺序只能自己排。
     */
    fun drawOverlay(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val frost = spec.frost.coerceIn(0f, 1f)
        val liquid = spec.mode == GlassMode.LIQUID
        buildPaths(w, h)

        canvas.save()
        canvas.clipPath(panelPath)

        // 3. 竖向柔光
        val sheen = if (liquid) 0.30f + 0.55f * frost else 0.20f + 0.30f * frost
        sheenPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            if (dark) {
                intArrayOf(
                    withAlpha(Color.WHITE, 0.12f * sheen),
                    withAlpha(Color.WHITE, 0.02f),
                    withAlpha(Color.BLACK, 0.16f),
                )
            } else {
                intArrayOf(
                    withAlpha(Color.WHITE, 0.30f * sheen),
                    withAlpha(Color.WHITE, 0.04f),
                    withAlpha(Color.WHITE, 0.10f),
                )
            },
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, sheenPaint)
        sheenPaint.shader = null

        // 4. 边缘折射：左右收暗，顶部一道内高光
        val edge = if (dark) 0.14f else 0.05f
        edgePaint.shader = LinearGradient(
            0f,
            0f,
            w,
            0f,
            intArrayOf(
                withAlpha(Color.BLACK, edge),
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                withAlpha(Color.BLACK, edge),
            ),
            floatArrayOf(0f, 0.16f, 0.84f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, edgePaint)
        edgePaint.shader = null

        if (liquid) {
            // 斜向反光：玻璃"接光"的方向。压深色封面时最明显，浅色背景上几乎是隐形的
            edgePaint.shader = LinearGradient(
                0f,
                0f,
                w * 0.62f,
                h,
                intArrayOf(
                    withAlpha(Color.WHITE, if (dark) 0.07f else 0.10f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, edgePaint)
            edgePaint.shader = null

            val bevel = context.dp(18f)
            edgePaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                bevel,
                intArrayOf(
                    withAlpha(Color.WHITE, if (dark) 0.14f else 0.24f),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, bevel, edgePaint)
            edgePaint.shader = null

            // 底部内侧压暗一条，玻璃才有"厚边"的收口
            val foot = context.dp(10f)
            edgePaint.shader = LinearGradient(
                0f,
                h - foot,
                0f,
                h,
                intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, if (dark) 0.12f else 0.035f)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, h - foot, w, h, edgePaint)
            edgePaint.shader = null
        }

        // 5. 触摸镜面（只有液态有：模糊模式是块"死"玻璃）
        if (liquid && lensStrength > 0.001f) {
            val radius = maxOf(w, h) * 0.62f
            lensPaint.shader = RadialGradient(
                lensX,
                lensY,
                radius,
                intArrayOf(
                    withAlpha(Color.WHITE, 0.20f * lensStrength),
                    withAlpha(Color.WHITE, 0.05f * lensStrength),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w, h, lensPaint)
            lensPaint.shader = null
        }

        canvas.restore()

        drawRim(canvas, h, liquid, frost)
    }

    private fun drawRim(canvas: Canvas, h: Float, liquid: Boolean, frost: Float) {
        // 外圈发丝线：把玻璃从背景里"切"出来，没有它边缘会糊成一片
        hairlinePaint.strokeWidth = context.dp(0.8f)
        hairlinePaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.20f)
        } else {
            withAlpha(Color.BLACK, 0.13f)
        }
        canvas.drawPath(edgePath, hairlinePaint)

        // 镜面高光：顶部最亮，到底部回一点亮，像一圈被环境光打亮的玻璃边
        rimPaint.strokeWidth = context.dp(1f)
        rimPaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(
                withAlpha(Color.WHITE, if (dark) 0.46f else 0.92f),
                withAlpha(Color.WHITE, if (dark) 0.07f else 0.12f),
                withAlpha(Color.WHITE, if (dark) 0.24f else 0.42f),
            ),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(rimPath, rimPaint)
        rimPaint.shader = null

        // 液态才有的极轻色散：边缘偏冷/偏暖的一像素错位，放大看才有，平时只是"边缘有颜色"
        if (!liquid) return
        val fringeAlpha = (0.05f + 0.05f * frost)
        fringePaint.strokeWidth = context.dp(0.8f)
        canvas.save()
        canvas.translate(context.dp(0.7f), 0f)
        fringePaint.color = withAlpha(Color.rgb(120, 220, 255), fringeAlpha)
        canvas.drawPath(rimPath, fringePaint)
        canvas.restore()
        canvas.save()
        canvas.translate(-context.dp(0.7f), 0f)
        fringePaint.color = withAlpha(Color.rgb(255, 170, 210), fringeAlpha * 0.8f)
        canvas.drawPath(rimPath, fringePaint)
        canvas.restore()
    }

    private fun buildPaths(w: Float, h: Float) {
        rect.set(0f, 0f, w, h)
        Squircle.path(rect, cornerRadius, panelPath)

        // 发丝线和镜面高光必须错开：同一位置画两道描边，白的会把黑的盖掉，
        // 浅色背景上边框就消失了。内圈 0.5dp 画切边，1.3dp 画高光。
        val edgeInset = context.dp(0.5f)
        edgeRect.set(edgeInset, edgeInset, w - edgeInset, h - edgeInset)
        Squircle.path(edgeRect, (cornerRadius - edgeInset).coerceAtLeast(0f), edgePath)

        val rimInset = context.dp(1.3f)
        rimRect.set(rimInset, rimInset, w - rimInset, h - rimInset)
        Squircle.path(rimRect, (cornerRadius - rimInset).coerceAtLeast(0f), rimPath)
    }
}