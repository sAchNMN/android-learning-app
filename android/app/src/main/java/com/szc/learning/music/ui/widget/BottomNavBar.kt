package com.szc.learning.music.ui.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.szc.learning.R
import com.szc.learning.music.ui.glass.GlassSurfaceView
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.motion.SpringValue
import com.szc.learning.music.ui.motion.springHaptic
import com.szc.learning.music.ui.motion.springScaleTo
import com.szc.learning.ui.theme.themeColor

/**
 * 液态玻璃底部导航。
 *
 * 玻璃本身交给 [GlassSurfaceView] / [com.szc.learning.music.ui.glass.GlassPainter]，
 * 和迷你播放器共用同一份材质设置：设置里换"液态玻璃/高斯模糊"、拖磨砂滑块，
 * 这里和播放栏会一起变。
 *
 * 本类只负责导航特有的动效：
 * 1. 选中胶囊：半透明实色 + 很轻的投影，滑动时用解析弹簧；
 * 2. 切 tab 时一道高光扫过整条玻璃；
 * 3. 图标按下的弹性缩放。
 */
class BottomNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassSurfaceView(context, attrs) {

    data class Item(val iconRes: Int, val label: String)

    var onSelected: ((Int) -> Unit)? = null

    private val row = LinearLayout(context)
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private var items: List<Item> = emptyList()
    private var selectedIndex = 0
    private var dark = false

    // ---------------------------------------------------------------- 画布
    private val barPath = Path()
    private val pillPath = Path()
    private val barRect = RectF()
    private val pillRect = RectF()

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var barRadius = 0f

    // ---------------------------------------------------------------- 弹簧
    private val pillX = SpringValue(0f, 0.52f, 0.14f)
    private val press = SpringValue(1f, 0.28f, 0.42f)
    private val sweep = SpringValue(0f, 0.9f, 0f)
    private var loopScheduled = false
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            loopScheduled = false
            if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos
            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f)
                .coerceIn(0f, 1f / 30f)
            lastFrameNanos = frameTimeNanos

            var moving = pillX.update(dt)
            moving = press.update(dt) or moving
            moving = sweep.update(dt) or moving
            if (sweep.isAtRest && sweep.targetValue > 0.5f) sweep.snapTo(0f)

            invalidate()
            if (moving) {
                scheduleFrame()
            } else {
                lastFrameNanos = 0L
            }
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        cornerRadiusDp = 26f
        // 默认焦点高亮会在启动时套住第一个 tab，看起来像第二层胶囊；导航只保留自绘选中态
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (barPath.isEmpty) {
                    outline.setRoundRect(0, 0, view.width, view.height, barRadius)
                    return
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        outline.setPath(barPath)
                    } else {
                        @Suppress("DEPRECATION")
                        outline.setConvexPath(barPath)
                    }
                }.onFailure {
                    outline.setRoundRect(0, 0, view.width, view.height, barRadius)
                }
            }
        }
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        applyColors()
    }

    // ---------------------------------------------------------------- 公开 API

    fun setItems(list: List<Item>) {
        items = list
        row.removeAllViews()
        iconViews.clear()
        labelViews.clear()

        list.forEachIndexed { index, item ->
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = false
                isFocusableInTouchMode = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    defaultFocusHighlightEnabled = false
                }
                contentDescription = item.label
                setOnClickListener {
                    springHaptic()
                    select(index, notify = true)
                }
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            press.animateTo(0.955f)
                            scheduleFrame()
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL,
                        -> {
                            press.animateTo(1f)
                            scheduleFrame()
                        }
                    }
                    false
                }
            }
            val icon = ImageView(context).apply {
                setImageResource(item.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(
                    context.dp(23f).toInt(),
                    context.dp(23f).toInt(),
                )
            }
            val label = TextView(context).apply {
                text = item.label
                textSize = 10f
                includeFontPadding = false
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(3f).toInt() }
            }
            column.addView(icon)
            column.addView(label)
            row.addView(
                column,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            iconViews += icon
            labelViews += label
        }
        applyColors()
        post { movePill(selectedIndex, animate = false) }
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index !in items.indices) return
        val previous = selectedIndex
        val changed = index != selectedIndex
        selectedIndex = index
        applyColors()
        if (changed) {
            movePill(index, animate = true)
            animateIconSelection(previous, index)
        }
        if (notify) onSelected?.invoke(index)
    }

    fun selectedIndex(): Int = selectedIndex

    fun setDarkMode(enabled: Boolean) {
        if (dark == enabled) return
        dark = enabled
        glassDark = enabled
        applyColors()
        invalidate()
    }

    /** 页面内容变化后调用：模糊模式下重新采样背后的内容。 */
    fun invalidateGlass() {
        refreshGlass()
    }

    // ---------------------------------------------------------------- 颜色

    private fun applyColors() {
        val active = context.themeColor(if (dark) R.attr.auOnDark else R.attr.auInk)
        val idle = context.themeColor(
            if (dark) R.attr.auOnDarkSecondary else R.attr.auInkTertiary,
        )
        iconViews.forEachIndexed { index, icon ->
            icon.imageTintList = ColorStateList.valueOf(if (index == selectedIndex) active else idle)
            icon.alpha = if (index == selectedIndex) 1f else 0.78f
        }
        labelViews.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            label.setTextColor(if (selected) active else idle)
            label.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
            label.alpha = if (selected) 1f else 0.82f
        }
        (0 until row.childCount).forEach { index ->
            row.getChildAt(index).isSelected = index == selectedIndex
        }
        invalidate()
    }

    private fun animateIconSelection(previous: Int, current: Int) {
        if (previous in iconViews.indices) {
            iconViews[previous].springScaleTo(1f, 0.42f, Motion.SMOOTH)
        }
        if (current in iconViews.indices) {
            iconViews[current].springScaleTo(1.1f, 0.52f, 0.42f)
        }
    }

    // ---------------------------------------------------------------- 胶囊

    private fun pillWidth(): Float {
        if (items.isEmpty() || width == 0) return 0f
        val itemWidth = width.toFloat() / items.size
        return minOf(context.dp(64f), itemWidth - context.dp(16f))
    }

    private fun pillTargetLeft(index: Int): Float {
        if (items.isEmpty() || width == 0) return 0f
        val itemWidth = width.toFloat() / items.size
        return index * itemWidth + (itemWidth - pillWidth()) / 2f
    }

    private fun movePill(index: Int, animate: Boolean) {
        if (items.isEmpty() || width == 0) return
        val target = pillTargetLeft(index)
        if (!animate || Motion.animationsDisabled(context)) {
            pillX.snapTo(target)
            return
        }
        // Apple 的 surface launch：第一帧就有速度，不做 ease-in
        pillX.animateTo(target, launch = true)
        sweep.snapTo(0f)
        sweep.animateTo(1f)
        scheduleFrame()
    }

    private fun scheduleFrame() {
        if (loopScheduled) return
        if (Motion.animationsDisabled(context)) return
        loopScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    // ---------------------------------------------------------------- 尺寸

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barRadius = context.dp(26f)
        barRect.set(0f, 0f, w.toFloat(), h.toFloat())
        Squircle.path(barRect, barRadius, barPath)

        pillX.snapTo(pillTargetLeft(selectedIndex))
        invalidateOutline()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onGlassDrawn(canvas: Canvas) {
        // 玻璃 + 描边已经画完了，这里补上选中胶囊和扫光
        drawSweep(canvas)
        drawPill(canvas)
    }

    private fun drawSweep(canvas: Canvas) {
        val progress = sweep.value
        if (progress <= 0.001f || progress >= 0.999f) return
        val w = width.toFloat()
        val center = -w * 0.28f + progress * (w * 1.56f)
        sweepPaint.shader = LinearGradient(
            center - w * 0.09f,
            0f,
            center + w * 0.09f,
            height.toFloat(),
            intArrayOf(
                withAlpha(Color.WHITE, 0f),
                withAlpha(Color.WHITE, if (dark) 0.05f else 0.11f),
                withAlpha(Color.WHITE, 0f),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.save()
        canvas.clipPath(barPath)
        canvas.drawRect(0f, 0f, w, height.toFloat(), sweepPaint)
        canvas.restore()
        sweepPaint.shader = null
    }

    private fun drawPill(canvas: Canvas) {
        if (items.isEmpty() || width == 0) return
        val itemWidth = width.toFloat() / items.size
        val pillWidth = minOf(context.dp(64f), itemWidth - context.dp(16f))
        val baseHeight = context.dp(32f)
        val scale = press.value.coerceIn(0.9f, 1.05f)
        val centerX = pillX.value + pillWidth / 2f
        val actualWidth = pillWidth * scale
        val actualHeight = baseHeight * (0.94f + 0.06f * scale)
        pillRect.set(
            centerX - actualWidth / 2f,
            (height - actualHeight) / 2f,
            centerX + actualWidth / 2f,
            (height + actualHeight) / 2f,
        )
        pillPath.reset()
        pillPath.addRoundRect(pillRect, actualHeight / 2f, actualHeight / 2f, Path.Direction.CW)

        // 一抹很轻的投影，让胶囊从玻璃里浮起来
        pillShadowPaint.color = if (dark) withAlpha(Color.BLACK, 0.32f) else withAlpha(Color.BLACK, 0.07f)
        val shadowRect = RectF(pillRect)
        shadowRect.offset(0f, context.dp(1.2f))
        shadowRect.inset(-context.dp(1f), -context.dp(1f))
        canvas.save()
        canvas.clipPath(barPath)
        canvas.drawRoundRect(shadowRect, actualHeight / 2f, actualHeight / 2f, pillShadowPaint)
        canvas.restore()

        // 半透明胶囊：不采样、不放大背板，只让底层颜色自然透出来。
        val fill = if (dark) {
            withAlpha(Color.WHITE, 0.18f)
        } else {
            withAlpha(context.themeColor(R.attr.auNavPill), 0.90f)
        }
        pillPaint.color = fill
        canvas.drawPath(pillPath, pillPaint)

        pillRimPaint.strokeWidth = context.dp(0.8f)
        pillRimPaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.18f)
        } else {
            withAlpha(context.themeColor(R.attr.auInk), 0.09f)
        }
        canvas.drawPath(pillPath, pillRimPaint)
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        loopScheduled = false
        super.onDetachedFromWindow()
    }
}
