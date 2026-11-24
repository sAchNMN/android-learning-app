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
 * 毛玻璃底部导航。
 *
 * 玻璃本身交给 [GlassSurfaceView] / [com.szc.learning.music.ui.glass.GlassPainter]，
 * 和迷你播放器共用同一份材质设置：设置里拖磨砂滑块，这里和播放栏会一起变。
 *
 * 本类只负责导航特有的动效：
 * 1. 分段胶囊：一条内嵌轨道 + 选中段，滑动时用解析弹簧；
 * 2. 切 tab 时一道高光扫过整条玻璃；
 * 3. 图标按下的弹性缩放。
 */
class BottomNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassSurfaceView(context, attrs) {

    data class Item(val iconRes: Int, val label: String)

    var onSelected: ((Int) -> Unit)? = null

    /** 竖向容器：上面是播放行（有歌时才显示），下面是 tab 行。整块共用一层玻璃。 */
    private val column = LinearLayout(context)
    private val row = LinearLayout(context)

    /** 嵌在导航里的播放行。没有曲目时是 GONE，导航条只剩 tab 行。 */
    val player = MiniPlayerView(context)
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private var items: List<Item> = emptyList()
    private var selectedIndex = 0
    private var dark = false

    // ---------------------------------------------------------------- 画布
    private val barPath = Path()
    private val pillPath = Path()
    private val trackPath = Path()
    private val barRect = RectF()
    private val pillRect = RectF()
    private val trackRect = RectF()

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
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
        cornerRadiusDp = 28f
        // 外框用纯圆弧（exponent=2），和选中胶囊的 addRoundRect 同一种曲率；
        // 保持 4.2 连续曲率时外框偏方、胶囊偏圆，两者放一起曲率不一致。
        cornerExponent = 2f
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
        column.orientation = LinearLayout.VERTICAL
        row.orientation = LinearLayout.HORIZONTAL
        player.visibility = View.GONE
        column.addView(
            player,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(ROW_DP).toInt()),
        )
        column.addView(
            row,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(ROW_DP).toInt()),
        )
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyColors()
    }

    // ---------------------------------------------------------------- 公开 API

    fun setItems(list: List<Item>) {
        items = list
        row.removeAllViews()
        iconViews.clear()
        labelViews.clear()

        list.forEachIndexed { index, item ->
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
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
                    context.dp(22f).toInt(),
                    context.dp(22f).toInt(),
                )
            }
            val label = TextView(context).apply {
                text = item.label
                textSize = 12f
                includeFontPadding = false
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = context.dp(5f).toInt() }
            }
            cell.addView(icon)
            cell.addView(label)
            row.addView(
                cell,
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
            if (dark) R.attr.auOnDarkSecondary else R.attr.auInkSecondary,
        )
        iconViews.forEachIndexed { index, icon ->
            icon.imageTintList = ColorStateList.valueOf(if (index == selectedIndex) active else idle)
            icon.alpha = 1f
        }
        labelViews.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            label.setTextColor(if (selected) active else idle)
            label.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
            label.visibility = if (selected) View.VISIBLE else View.GONE
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

    // ---------------------------------------------------------------- 轨道与胶囊

    /** 每个 tab 分到的槽位宽度：轨道去掉左右内边距后四等分。 */
    private fun cellWidth(): Float {
        if (items.isEmpty() || width == 0) return 0f
        val trackWidth = width - 2 * context.dp(TRACK_INSET_X_DP)
        return (trackWidth / items.size).coerceAtLeast(0f)
    }

    /** 选中段的宽度：槽位内再留一点内缝，滑动时不会碰到相邻段。 */
    private fun pillWidth(): Float =
        (cellWidth() - 2 * context.dp(SEG_PAD_DP)).coerceAtLeast(0f)

    private fun pillTargetLeft(index: Int): Float {
        if (items.isEmpty() || width == 0) return 0f
        return context.dp(TRACK_INSET_X_DP) + index * cellWidth() + context.dp(SEG_PAD_DP)
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
        barRadius = context.dp(28f)
        barRect.set(0f, 0f, w.toFloat(), h.toFloat())
        Squircle.path(barRect, barRadius, barPath, cornerExponent)

        // 胶囊的横向位置只取决于宽度。高度变化（播放行出现/消失，切到播放 tab 时就会发生）
        // 不能 snap，否则会把刚启动的切换动画直接打断成瞬移。
        if (w != oldw) pillX.snapTo(pillTargetLeft(selectedIndex))
        invalidateOutline()
    }

    // ---------------------------------------------------------------- 绘制

    override fun onGlassDrawn(canvas: Canvas) {
        // 玻璃 + 描边已经画完了，这里补上分段轨道、选中胶囊和扫光
        drawSweep(canvas)
        drawTrack(canvas)
        drawPill(canvas)
    }

    /** 内嵌轨道：比玻璃略深一点的凹槽，把四个 tab 收成一组。 */
    private fun drawTrack(canvas: Canvas) {
        if (items.isEmpty() || width == 0 || row.height == 0) return
        val insetX = context.dp(TRACK_INSET_X_DP)
        val insetY = context.dp(TRACK_INSET_Y_DP)
        val top = row.top + insetY
        val bottom = row.top + row.height - insetY
        if (bottom <= top) return
        trackRect.set(insetX, top, width - insetX, bottom)
        trackPath.reset()
        trackPath.addRoundRect(trackRect, trackRect.height() / 2f, trackRect.height() / 2f, Path.Direction.CW)

        trackPaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.08f)
        } else {
            withAlpha(context.themeColor(R.attr.auInk), 0.07f)
        }
        canvas.drawPath(trackPath, trackPaint)

        trackRimPaint.strokeWidth = context.dp(0.8f)
        trackRimPaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.10f)
        } else {
            withAlpha(context.themeColor(R.attr.auInk), 0.09f)
        }
        canvas.drawPath(trackPath, trackRimPaint)
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
        if (items.isEmpty() || width == 0 || trackRect.width() == 0f) return
        val pillWidth = pillWidth()
        // 分段胶囊贴着轨道内沿：按下只做轻微收缩，不会越出轨道
        val scale = press.value.coerceIn(0.94f, 1f)
        val centerX = pillX.value + pillWidth / 2f
        val centerY = trackRect.centerY()
        val actualWidth = pillWidth * scale
        val actualHeight = (trackRect.height() - 2 * context.dp(SEG_PAD_DP)) * scale
        if (actualWidth <= 0f || actualHeight <= 0f) return
        pillRect.set(
            centerX - actualWidth / 2f,
            centerY - actualHeight / 2f,
            centerX + actualWidth / 2f,
            centerY + actualHeight / 2f,
        )
        pillPath.reset()
        pillPath.addRoundRect(pillRect, actualHeight / 2f, actualHeight / 2f, Path.Direction.CW)

        // 一抹很轻的投影，让胶囊从轨道里浮起来
        pillShadowPaint.color = if (dark) withAlpha(Color.BLACK, 0.32f) else withAlpha(Color.BLACK, 0.07f)
        val shadowRect = RectF(pillRect)
        shadowRect.offset(0f, context.dp(1.2f))
        shadowRect.inset(-context.dp(1f), -context.dp(1f))
        canvas.save()
        canvas.clipPath(barPath)
        canvas.drawRoundRect(shadowRect, actualHeight / 2f, actualHeight / 2f, pillShadowPaint)
        canvas.restore()

        // 选中段用主题的柔和色，和轨道的灰度拉开一层
        val fill = if (dark) {
            withAlpha(Color.WHITE, 0.16f)
        } else {
            withAlpha(context.themeColor(R.attr.auAccentSoft), 0.95f)
        }
        pillPaint.color = fill
        canvas.drawPath(pillPath, pillPaint)

        pillRimPaint.strokeWidth = context.dp(0.8f)
        pillRimPaint.color = if (dark) {
            withAlpha(Color.WHITE, 0.20f)
        } else {
            withAlpha(context.themeColor(R.attr.auAccent), 0.30f)
        }
        canvas.drawPath(pillPath, pillRimPaint)
    }

    private companion object {
        /** 播放行和 tab 行各自的高度（dp）。 */
        const val ROW_DP = 56f

        /** 分段轨道相对 tab 行的左右 / 上下内缩（dp）。 */
        const val TRACK_INSET_X_DP = 6f
        const val TRACK_INSET_Y_DP = 6f

        /** 选中段与轨道内沿的间隙（dp）。 */
        const val SEG_PAD_DP = 3f
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
