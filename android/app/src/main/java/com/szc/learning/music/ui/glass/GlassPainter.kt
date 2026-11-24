package com.szc.learning.music.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.szc.learning.R
import com.szc.learning.music.ui.widget.Squircle
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.music.ui.widget.withAlpha
import com.szc.learning.ui.theme.themeColor

/**
 * 半透明面板的绘制引擎，被迷你播放器和底部导航共用。
 *
 * 三层，从下往上：
 * 1. **染色**：半透明主题面色。`frost` 越大越厚、越不透，这是"磨砂程度"；
 * 2. **竖向柔光**：顶部亮、底部略压暗，面板才有厚度而不是一张半透明纸；
 * 3. **描边**：外圈发丝线 + 一圈随高度变化的镜面高光。
 *
 * 这个引擎**不采样、不模糊、不折射**。演进路径：最早是 iOS 26 那种液态玻璃
 * （AGSL 边缘折射 + 色散 + 触摸光斑），后来退到高斯模糊（RenderNode 录制 + RenderEffect），
 * 现在退到纯半透明。原因是实测出来一条硬结论：背后内容的实时采集本身就是这台机器
 * 每帧预算里最贵的那一项，换成更便宜的模糊算法并不能改变它。
 *
 * 代价是背后内容不再透出来 —— 面板是"奶白半透明条"，不是"透过磨砂看见列表"。
 * 换来的是每帧零额外开销：只画几层纯色和渐变。
 *
 * 绘制过程不分配对象：路径、Paint 都是复用的，只有渐变对象每次新建
 * （Android 的 Shader 不可变，这是最便宜的做法）。
 */
class GlassPainter(private val context: Context) {

    /** 当前外观。由 [GlassSurfaceView] 在设置变化时写入。 */
    var spec: GlassSpec = GlassSpec.DEFAULT
        private set

    /** 深色面板：夜间主题，或播放器 tab 这种压着深色封面的场景。 */
    var dark: Boolean = false
        private set

    var cornerRadius: Float = context.dp(20f)
        private set

    /** 圆角曲率指数。4.2 = 连续曲率（偏方），2 = 纯圆弧（胶囊）。 */
    var cornerExponent: Float = 4.2f
        private set

    private var surfaceColor = 0

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hairlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val panelPath = Path()
    private val edgePath = Path()
    private val rimPath = Path()
    private val rect = RectF()
    private val edgeRect = RectF()
    private val rimRect = RectF()

    fun configure(spec: GlassSpec, dark: Boolean, cornerRadius: Float, cornerExponent: Float = 4.2f) {
        this.spec = spec.clamped()
        this.dark = dark
        this.cornerRadius = cornerRadius
        this.cornerExponent = cornerExponent
        surfaceColor = context.themeColor(R.attr.auSurface)
    }

    /** 画整块面板。坐标系就是控件自己的 0..width / 0..height。 */
    fun draw(canvas: Canvas, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val frost = spec.frost.coerceIn(0f, 1f)
        buildPaths(w, h)

        canvas.save()
        canvas.clipPath(panelPath)

        // 1. 染色。没有模糊兜着，浓度直接等于"背后内容能看到多少"：
        //    frost 0 = 0.45（通透，能隐约看到背后内容，靠描边和柔光撑住轮廓）
        //    frost 1 = 0.96（基本实心）
        fillPaint.color = if (dark) {
            // 深色下不能用更深的底色：面板会变成背景上的一个"洞"。
            // 真实世界的深色玻璃是一层薄薄的白雾，比周围略亮。
            withAlpha(Color.WHITE, 0.12f + 0.26f * frost)
        } else {
            withAlpha(surfaceColor, 0.45f + 0.51f * frost)
        }
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        // 2. 竖向柔光
        val sheen = 0.20f + 0.30f * frost
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

        // 3. 边缘收暗：左右各压一条，把面板从背景里"托"起来
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

        canvas.restore()

        drawRim(canvas, h)
    }

    private fun drawRim(canvas: Canvas, h: Float) {
        // 外圈发丝线：把面板从背景里"切"出来，没有它边缘会糊成一片
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
    }

    private fun buildPaths(w: Float, h: Float) {
        rect.set(0f, 0f, w, h)
        Squircle.path(rect, cornerRadius, panelPath, cornerExponent)

        // 发丝线和镜面高光必须错开：同一位置画两道描边，白的会把黑的盖掉，
        // 浅色背景上边框就消失了。内圈 0.5dp 画切边，1.3dp 画高光。
        val edgeInset = context.dp(0.5f)
        edgeRect.set(edgeInset, edgeInset, w - edgeInset, h - edgeInset)
        Squircle.path(edgeRect, (cornerRadius - edgeInset).coerceAtLeast(0f), edgePath, cornerExponent)

        val rimInset = context.dp(1.3f)
        rimRect.set(rimInset, rimInset, w - rimInset, h - rimInset)
        Squircle.path(rimRect, (cornerRadius - rimInset).coerceAtLeast(0f), rimPath, cornerExponent)
    }
}
