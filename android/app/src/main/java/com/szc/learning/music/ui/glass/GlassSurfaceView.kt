package com.szc.learning.music.ui.glass

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.szc.learning.music.ui.widget.Squircle
import com.szc.learning.music.ui.widget.dp

/**
 * 一块半透明面板。迷你播放器和底部导航都继承它，所以设置面板里拖一次滑块，
 * 整个 App 的面板会同时变厚/变薄 —— 这是"材质"而不是某个控件的装饰。
 *
 * 职责只有两块：
 * 1. 画：[GlassPainter] 负责染色、柔光和描边，这里只管把圆角和深浅喂给它；
 * 2. 热更新：监听 SharedPreferences，设置面板一改就重绘，不需要谁去通知谁。
 *
 * **背后内容不再透出来**。曾经这里会录 RenderNode 再挂 RenderEffect 做实时模糊
 * （更早还做过 AGSL 折射），代价是每帧要重录背后那棵视图树 + 一次离屏合成 ——
 * 实测那是这台机器 11.1ms 帧预算里最贵的一项，`dumpsys gfxinfo` 的
 * Slow issue draw commands / Slow bitmap uploads 全从这来。
 * 现在面板只是几层纯色和渐变，每帧成本约等于画一个圆角矩形。
 */
open class GlassSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    protected val glass = GlassPainter(context)

    /** 面板圆角（dp）。底部导航是 26，迷你播放器是 20。 */
    var cornerRadiusDp: Float = 20f
        set(value) {
            if (field == value) return
            field = value
            syncGlass()
            invalidateOutline()
            invalidate()
        }

    /**
     * 圆角曲率指数：4.2 = 连续曲率（偏方），2 = 纯圆弧。
     * 默认跟随 Squircle 的连续曲率；底部导航改成 2，和选中胶囊的 addRoundRect 对齐。
     */
    var cornerExponent: Float = 4.2f
        set(value) {
            if (field == value) return
            field = value
            syncGlass()
            invalidateOutline()
            invalidate()
        }

    /** 深色面板：夜间主题，或播放器 tab 这种压着深色封面的场景。 */
    var glassDark: Boolean = isNight(context)
        set(value) {
            if (field == value) return
            field = value
            syncGlass()
            invalidate()
        }

    /** 设置面板里的实时预览用它临时覆盖已保存的外观，不落盘。 */
    var glassOverride: GlassSpec? = null
        set(value) {
            field = value
            syncGlass()
        }

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncGlass() }

    init {
        setWillNotDraw(false)
        clipToOutline = true
        // 面板是半透明的，系统 elevation 阴影会从底下透出来，
        // 整块会发灰、发脏 —— 阴影必须关掉，边界交给描边和高光。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            outlineAmbientShadowColor = Color.TRANSPARENT
            outlineSpotShadowColor = Color.TRANSPARENT
        }
        outlineProvider = object : ViewOutlineProvider() {
            private val path = Path()
            private val rect = RectF()

            override fun getOutline(view: View, outline: Outline) {
                val radius = context.dp(cornerRadiusDp)
                // 裁剪轮廓要和画出来的形状一致：圆角矩形会把超级椭圆的四角切掉
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    rect.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
                    Squircle.path(rect, radius, path, cornerExponent)
                    outline.setPath(path)
                } else {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }
        syncGlass()
    }

    // ---------------------------------------------------------------- 公开 API

    /** 主题或设置变化后调用：重读外观并重绘。 */
    fun refreshGlass() {
        syncGlass()
    }

    // ---------------------------------------------------------------- 状态

    private fun syncGlass() {
        glass.configure(
            spec = glassOverride ?: GlassSettings.spec(context),
            dark = glassDark,
            cornerRadius = context.dp(cornerRadiusDp),
            cornerExponent = cornerExponent,
        )
        invalidate()
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GlassSettings.preferences(context)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        syncGlass()
    }

    override fun onDetachedFromWindow() {
        GlassSettings.preferences(context)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) syncGlass()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isAttachedToWindow) syncGlass()
    }

    // ---------------------------------------------------------------- 绘制

    /**
     * 面板画在子 View 之前：子 view 是封面、文字、按钮，必须压在面板之上。
     * [onGlassDrawn] 也在这里 —— 它画的是"面板之上、内容之下"的东西（底部导航的选中胶囊）。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        glass.draw(canvas, width, height)
        onGlassDrawn(canvas)
    }

    /** 给子类在"面板之上、内容之下"画东西的机会（底部导航的选中胶囊）。 */
    protected open fun onGlassDrawn(canvas: Canvas) = Unit

    private companion object {
        fun isNight(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }
}
