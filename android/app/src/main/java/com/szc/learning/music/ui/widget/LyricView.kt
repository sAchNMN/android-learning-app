package com.szc.learning.music.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.music.data.LyricLine
import com.szc.learning.music.data.LyricSheet
import com.szc.learning.ui.theme.themeColor
import kotlin.math.abs
import kotlin.math.exp

/**
 * 实时滚动歌词。
 *
 * **跟随模式**：当前行始终停在控件垂直中线，靠 `LinearSmoothScroller` 把目标行推到
 * 上内边距处；上下内边距留成半屏，首尾行也能居中。
 *
 * **浏览模式**：用户上下拖动歌词时进入。此时不再自动居中当前行，
 * 停在中线的那一句变成高亮目标，右侧出现一个 ▶；点 ▶ 就从这一句开始播放。
 * 手指离开后几秒无操作，或点了 ▶ / 某一行，自动回到跟随模式。
 *
 * 播放 tab 的页面是竖向 ViewPager2：手指落在歌词上时就禁止父级拦截，
 * 歌词优先吃掉上下拖动；要翻页，从歌词区以外的地方划。
 */
class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    /** 点击某一行 / 点中线的 ▶：有时间码就 seek，没有就由页面决定怎么处理。 */
    var onLineClick: ((LyricLine) -> Unit)? = null

    /** 当前行颜色：就是当前主题的主色。 */
    private val accentColor = context.themeColor(R.attr.auAccent)
    private val lineColor = context.themeColor(R.attr.auOnDark)

    private val lines = ArrayList<LyricLine>()
    private var timed = false
    private var placeholder: CharSequence = ""
    private var activeIndex = -1
    private var currentSheet: LyricSheet? = null

    // ---------------------------------------------------------------- 浏览模式
    private var browsing = false
    private var targetIndex = -1
    private var indicatorAlpha = 0f
    private var indicatorAnimator: ValueAnimator? = null
    private val exitBrowsing = Runnable { leaveBrowsing() }

    // 点 ▶ / 点某行跳转后，播放器要过一小会儿才会上报新位置。这段时间里到的还是旧位置，
    // 直接采信会把高亮和滚动拽回旧的那一行，再跳回来 —— 所以先忽略「仍指向旧行」的进度。
    private var staleIndex = -1
    private var staleUntil = 0L

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trianglePath = Path()

    private var scrollAnimator: ValueAnimator? = null
    private val scrollInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val lineAdapter = LineAdapter()

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (browsing && timed && hitIndicator(e.x, e.y)) {
                    val jumpTo = targetIndex
                    lines.getOrNull(jumpTo)?.let { line ->
                        performClick()
                        onLineClick?.invoke(line)
                        leaveBrowsing(jumpTo = jumpTo)
                    }
                    return true
                }
                val child = findChildViewUnder(e.x, e.y) ?: return false
                val position = getChildAdapterPosition(child)
                val line = lines.getOrNull(position) ?: return false
                performClick()
                onLineClick?.invoke(line)
                if (browsing) leaveBrowsing(jumpTo = position)
                return true
            }
        },
    )

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = lineAdapter
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        isNestedScrollingEnabled = false
        itemAnimator = null
        isFocusable = false
        // 左右留出 ▶ 的位置，两侧对称，歌词文字不会压到按钮
        setPadding(context.dp(SIDE_PAD_DP).toInt(), 0, context.dp(SIDE_PAD_DP).toInt(), 0)

        addOnScrollListener(
            object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    when (newState) {
                        // 只有手指拖动才会进 DRAGGING，程序触发的平滑滚动不会
                        SCROLL_STATE_DRAGGING -> if (timed && lines.isNotEmpty()) enterBrowsing()
                        SCROLL_STATE_IDLE -> if (browsing) scheduleExit()
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (browsing) updateTarget()
                    // 滚动中每一行的偏移都在变，透明度必须逐帧跟着重算
                    refreshAlphas()
                }
            },
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCenterPadding()
        if (activeIndex >= 0 && !browsing) centerInstantly(activeIndex)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // 行位置变了（换歌、换字号、跳转），透明度要跟着重算
        refreshAlphas()
    }

    /**
     * 按每行**实际落在屏幕上的位置**刷新不透明度：中线那一行最实，越往上下越淡。
     *
     * 早先按「距当前行几行」分 4 档（1 / 0.50 / 0.32 / 0.20），档与档之间是硬跳变，
     * 滚动中能看见亮度一格一格地闪。改成按像素距离做连续衰减后，滚动过程本身就是渐变。
     */
    private fun refreshAlphas() {
        if (childCount == 0 || height <= 0) return
        val mid = height / 2f
        // 用最矮的一行当单位行高：当前行字号大（17sp），拿它当基准会把衰减拉得太缓
        var unit = 0
        for (i in 0 until childCount) {
            val h = getChildAt(i).height
            if (h > 0 && (unit == 0 || h < unit)) unit = h
        }
        if (unit <= 0) unit = context.dp(LINE_HEIGHT_DP).toInt().coerceAtLeast(1)

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val holder = getChildViewHolder(child) as? LineHolder ?: continue
            var alpha = alphaFor(child, mid, unit)
            // 播放行是「高亮」，跳转过渡期它可能还没滚到中线，别让它当场暗下去
            // （但仍受边缘淡出约束：滚出控件的行一定要消失）
            val isActive = !browsing && getChildAdapterPosition(child) == activeIndex
            if (isActive) {
                val edge = context.dp(EDGE_FADE_DP)
                val centerY = child.top + child.height / 2f
                val edgeFactor = (minOf(centerY, height - centerY) / edge).coerceIn(0f, 1f)
                alpha = maxOf(alpha, ACTIVE_MIN_ALPHA * edgeFactor)
            }
            holder.applyAlpha(alpha)
            // 缩放跟着同一个「距中线几行」的连续量走：一行 0.94，再远就不继续缩，
            // 否则远处的行会缩成看不清的小字。当前行恒定 1，避免高亮行在滚动中被压小。
            val rows = abs(child.top + child.height / 2f - mid) / unit
            val scale = if (isActive) 1f else 1f - SCALE_RATE * minOf(rows, 1f)
            holder.applyScale(scale)
        }
    }

    /**
     * 一行该有多亮，三层规则：
     * 1. **焦点衰减**：距中线 d 行，跟随模式按 e^(-0.7d) 快速淡出（聚焦当前句）；
     *    浏览模式换成缓衰减并抬高底线（要读得清才能选），两种曲线按 [indicatorAlpha] 平滑混合，
     *    进出浏览模式时亮度是渐变而不是跳变。
     * 2. **边缘淡出**：行中心靠近控件上下沿 [EDGE_FADE_DP] 内时再乘一个 0~1 的系数，
     *    文字是被「溶掉」而不是被裁边切掉。
     * 3. 低于 [HIDE_BELOW] 的行由 [LineHolder.applyAlpha] 直接标为不可见，不再参与绘制。
     */
    private fun alphaFor(child: View, mid: Float, unit: Int): Float {
        val centerY = child.top + child.height / 2f
        val rows = abs(centerY - mid) / unit
        val follow = FOLLOW_MIN_ALPHA + (1f - FOLLOW_MIN_ALPHA) * exp(-FOLLOW_RATE * rows)
        val browse = BROWSE_MIN_ALPHA + (1f - BROWSE_MIN_ALPHA) * exp(-BROWSE_RATE * rows)
        val focus = follow + (browse - follow) * indicatorAlpha
        val edge = context.dp(EDGE_FADE_DP)
        val fromEdge = minOf(centerY, height - centerY)
        val edgeFactor = (fromEdge / edge).coerceIn(0f, 1f)
        return focus * edgeFactor
    }

    /** 上下各留半屏，任何一行都能滚到中线。 */
    private fun applyCenterPadding() {
        val pad = ((height - context.dp(LINE_HEIGHT_DP)) / 2f).toInt().coerceAtLeast(0)
        if (paddingTop != pad || paddingBottom != pad) {
            setPadding(paddingLeft, pad, paddingRight, pad)
        }
    }

    fun setSheet(sheet: LyricSheet, positionMs: Long = 0L) {
        if (sheet.isEmpty) return
        if (currentSheet === sheet) {
            updateProgress(positionMs)
            return
        }
        currentSheet = sheet
        placeholder = ""
        lines.clear()
        lines.addAll(sheet.lines)
        timed = sheet.timed
        activeIndex = -1
        resetBrowsing()
        // 换歌＝整份歌词替换，每一行都不同，DiffUtil 只会在 O(n²) 的比较后得出
        // 「全部重绑」这个结论。这里全量刷新是合理的，不是偷懒。
        @Suppress("NotifyDataSetChanged")
        lineAdapter.notifyDataSetChanged()
        applyCenterPadding()
        updateProgress(positionMs, force = true)
    }

    fun showPlaceholder(text: CharSequence) {
        if (lines.isEmpty() && !timed && placeholder == text) return
        currentSheet = null
        lines.clear()
        timed = false
        activeIndex = -1
        resetBrowsing()
        placeholder = text
        // 占位态只有一行，整份替换同样只有这一行
        @Suppress("NotifyDataSetChanged")
        lineAdapter.notifyDataSetChanged()
        applyCenterPadding()
    }

    fun updateProgress(positionMs: Long) = updateProgress(positionMs, force = false)

    private fun updateProgress(positionMs: Long, force: Boolean) {
        if (!timed || lines.isEmpty()) return
        val index = indexAt(positionMs)
        if (staleIndex >= 0) {
            // 跳转刚发出：还指着旧行就是旧进度，丢掉；换了行（或超时）才算新位置到了
            if (index == staleIndex && SystemClock.uptimeMillis() < staleUntil) return
            staleIndex = -1
        }
        if (index == activeIndex && !force) return
        val previous = activeIndex
        activeIndex = index
        refreshVisibleStyles()
        // 浏览中：播放行照常更新高亮，但不抢滚动，用户看到哪就停在哪
        if (index < 0 || browsing) return
        // 播放中通常是下一句；跨度太大说明用户拖了进度，直接跳，别慢慢滚
        val jump = previous < 0 || abs(index - previous) > 2
        // 当前行字号变化会触发 requestLayout，等这一帧布局完再滚，避免滚动被取消
        post {
            if (activeIndex == index && !browsing) scrollToLine(index, smooth = !force && !jump)
        }
    }

    private fun indexAt(positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun scrollToLine(index: Int, smooth: Boolean) {
        val manager = layoutManager as? LinearLayoutManager ?: return
        if (index !in lines.indices) return
        // 顶部内边距已经是半屏，SNAP_TO_START 的落点就是视觉中线
        if (!smooth || height == 0) {
            manager.scrollToPositionWithOffset(index, 0)
            return
        }
        val child = manager.findViewByPosition(index)
        if (child == null) {
            // 目标行不在屏幕上（离得很远）：慢慢滚没有意义，直接就位
            manager.scrollToPositionWithOffset(index, 0)
            return
        }
        animateScrollBy(child.top - paddingTop)
    }

    /**
     * 自己驱动的滚动：先快后慢的减速曲线（近似 cubic-bezier(.22,1,.36,1)），
     * 时长随距离略增。比 LinearSmoothScroller 的匀速推进更像「歌词被轻轻托到位」，
     * 并且新目标来时能从当前位置无缝接上，不会被取消成一次顿挫。
     */
    private fun animateScrollBy(distance: Int) {
        cancelScrollAnimation()
        if (distance == 0) return
        val unit = context.dp(LINE_HEIGHT_DP)
        val rows = (abs(distance) / unit).coerceAtMost(3f)
        var applied = 0
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (SCROLL_BASE_MS + rows * SCROLL_PER_ROW_MS).toLong()
            interpolator = scrollInterpolator
            addUpdateListener {
                val target = (distance * it.animatedFraction).toInt()
                val step = target - applied
                if (step != 0) {
                    applied = target
                    scrollBy(0, step)
                }
            }
            start()
        }
    }

    private fun cancelScrollAnimation() {
        scrollAnimator?.cancel()
        scrollAnimator = null
    }

    private fun centerInstantly(index: Int) {
        val manager = layoutManager as? LinearLayoutManager ?: return
        manager.scrollToPositionWithOffset(index, 0)
    }

    private fun refreshVisibleStyles() {
        for (i in 0 until childCount) {
            val holder = getChildViewHolder(getChildAt(i)) as? LineHolder ?: continue
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION) holder.render(position)
        }
        refreshAlphas()
    }

    // ---------------------------------------------------------------- 浏览模式

    private fun enterBrowsing() {
        removeCallbacks(exitBrowsing)
        cancelScrollAnimation()
        if (!browsing) {
            browsing = true
            animateIndicator(1f)
        }
        updateTarget()
    }

    private fun scheduleExit() {
        removeCallbacks(exitBrowsing)
        postDelayed(exitBrowsing, BROWSE_TIMEOUT_MS)
    }

    /**
     * 回到跟随模式。
     *
     * @param jumpTo 用户刚选了要跳到的那一行：直接把它当作当前行并停在它这里，
     *   不先滚回旧的播放行。null = 超时自动退出，滚回正在播放的那一行。
     */
    private fun leaveBrowsing(jumpTo: Int? = null) {
        removeCallbacks(exitBrowsing)
        if (!browsing) return
        browsing = false
        targetIndex = -1
        animateIndicator(0f)
        if (jumpTo != null && jumpTo in lines.indices) {
            if (jumpTo != activeIndex) {
                staleIndex = activeIndex
                staleUntil = SystemClock.uptimeMillis() + STALE_PROGRESS_MS
                activeIndex = jumpTo
            }
            refreshVisibleStyles()
            // 目标行本来就在中线附近，这里只是把它吸到正中
            scrollToLine(jumpTo, smooth = true)
        } else {
            refreshVisibleStyles()
            if (activeIndex >= 0) scrollToLine(activeIndex, smooth = true)
        }
    }

    /** 换歌 / 换歌词时直接清掉浏览状态，不做动画。 */
    private fun resetBrowsing() {
        removeCallbacks(exitBrowsing)
        staleIndex = -1
        browsing = false
        targetIndex = -1
        indicatorAnimator?.cancel()
        cancelScrollAnimation()
        indicatorAlpha = 0f
    }

    /** 当前停在中线的那一行就是跳转目标。 */
    private fun updateTarget() {
        val child = findChildViewUnder(width / 2f, height / 2f) ?: return
        val index = getChildAdapterPosition(child)
        if (index == RecyclerView.NO_POSITION || index == targetIndex) return
        targetIndex = index
        refreshVisibleStyles()
    }

    private fun animateIndicator(to: Float) {
        indicatorAnimator?.cancel()
        indicatorAnimator = ValueAnimator.ofFloat(indicatorAlpha, to).apply {
            duration = INDICATOR_FADE_MS
            addUpdateListener {
                indicatorAlpha = it.animatedValue as Float
                // 亮度曲线跟它混合，进出浏览模式时整体平滑过渡
                refreshAlphas()
                invalidate()
            }
            start()
        }
    }

    private fun indicatorCenterX(): Float = width - context.dp(INDICATOR_INSET_DP)

    private fun hitIndicator(x: Float, y: Float): Boolean {
        val slop = context.dp(INDICATOR_RADIUS_DP + 10f)
        val dx = x - indicatorCenterX()
        val dy = y - height / 2f
        return dx * dx + dy * dy <= slop * slop
    }

    /** 中线右侧的 ▶：半透明圆盘 + 白色三角，只在浏览模式出现。 */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (indicatorAlpha <= 0.01f || !timed) return
        val cx = indicatorCenterX()
        val cy = height / 2f
        val radius = context.dp(INDICATOR_RADIUS_DP)

        indicatorPaint.style = Paint.Style.FILL
        indicatorPaint.color = withAlpha(Color.WHITE, 0.18f * indicatorAlpha)
        canvas.drawCircle(cx, cy, radius, indicatorPaint)

        val half = radius * 0.42f
        trianglePath.rewind()
        // 三角形的重心略右移，视觉上才居中
        val shift = half * 0.14f
        trianglePath.moveTo(cx - half * 0.7f + shift, cy - half)
        trianglePath.lineTo(cx + half + shift, cy)
        trianglePath.lineTo(cx - half * 0.7f + shift, cy + half)
        trianglePath.close()
        indicatorPaint.color = withAlpha(Color.WHITE, indicatorAlpha)
        canvas.drawPath(trianglePath, indicatorPaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) cancelScrollAnimation()
        if (e.actionMasked == MotionEvent.ACTION_DOWN && lines.isNotEmpty()) {
            // 歌词要自己吃上下拖动：不让父级竖向 ViewPager2 抢去翻页
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        taps.onTouchEvent(e)
        super.onTouchEvent(e)
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(exitBrowsing)
        indicatorAnimator?.cancel()
        cancelScrollAnimation()
        super.onDetachedFromWindow()
    }

    private inner class LineAdapter : Adapter<LineHolder>() {

        override fun getItemCount(): Int = if (lines.isEmpty()) 1 else lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lyric_line, parent, false) as TextView
            return LineHolder(view)
        }

        override fun onBindViewHolder(holder: LineHolder, position: Int) {
            if (lines.isEmpty()) {
                holder.renderPlaceholder(placeholder)
            } else {
                holder.render(position)
            }
        }
    }

    private inner class LineHolder(
        private val text: TextView,
    ) : ViewHolder(text) {

        private var appliedAlpha = -1f
        private var appliedScale = -1f

        /** 占位行不参与位置渐隐，用固定淡色。 */
        private var fading = true

        fun render(position: Int) {
            val line = lines[position]
            text.text = line.text
            val active = position == activeIndex
            val target = browsing && position == targetIndex

            // 颜色本身不透明，透明度统一交给 View.alpha —— setTextColor 会重绘，
            // 而 alpha 只是 renderNode 属性，滚动时每帧改都不掉帧
            text.setTextColor(
                when {
                    active -> accentColor
                    target -> Color.WHITE
                    else -> lineColor
                },
            )
            fading = true
            appliedAlpha = -1f
            appliedScale = -1f
            // 字号只跟播放行走：浏览时目标行只改颜色，避免滚动中不停 requestLayout 抖动
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (active) ACTIVE_SP else IDLE_SP)
            text.setTypeface(null, if (active || target) Typeface.BOLD else Typeface.NORMAL)
        }

        /** 施加几何渐隐。变化很小时跳过。 */
        fun applyAlpha(alpha: Float) {
            if (!fading) return
            // 太淡就干脆隐藏：alpha 归零 + INVISIBLE（不用 GONE，否则会触发重新布局、位置塌陷）
            val shown = if (alpha < HIDE_BELOW) 0f else alpha
            if (abs(shown - appliedAlpha) < ALPHA_EPSILON && (shown > 0f) == (text.visibility == View.VISIBLE)) return
            appliedAlpha = shown
            text.alpha = shown
            text.visibility = if (shown > 0f) View.VISIBLE else View.INVISIBLE
        }

        /**
         * 施加缩放。
         *
         * 用 `setScaleX/Y` 而不是改字号：缩放是 renderNode 属性，不触发测量与布局，
         * 滚动中每帧改都不会掉帧；改字号会 requestLayout，整屏歌词跟着抖。
         */
        fun applyScale(scale: Float) {
            if (!fading) return
            if (abs(scale - appliedScale) < SCALE_EPSILON) return
            appliedScale = scale
            text.scaleX = scale
            text.scaleY = scale
        }

        fun renderPlaceholder(value: CharSequence) {
            text.text = value
            text.setTextColor(lineColor)
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, IDLE_SP)
            text.setTypeface(null, Typeface.NORMAL)
            fading = false
            text.visibility = View.VISIBLE
            text.alpha = 0.42f
            // 占位行不参与缩放，否则纯文本歌词会被缩一下
            text.scaleX = 1f
            text.scaleY = 1f
        }
    }

    private companion object {
        /** 单行歌词的估算高度，用来算居中内边距；行高变化只是轻微偏心。 */
        const val LINE_HEIGHT_DP = 40f
        const val ACTIVE_SP = 17f
        const val IDLE_SP = 13f

        /** 跟随模式：快衰减，聚焦当前句。 */
        const val FOLLOW_MIN_ALPHA = 0.0f
        const val FOLLOW_RATE = 0.7f

        /** 浏览模式：缓衰减 + 抬高底线，保证能读。 */
        const val BROWSE_MIN_ALPHA = 0.30f
        const val BROWSE_RATE = 0.30f

        /** 行中心离控件上下沿多近时开始淡出。 */
        const val EDGE_FADE_DP = 56f

        /** 低于这个亮度就直接隐藏（INVISIBLE），不再绘制。 */
        const val HIDE_BELOW = 0.04f

        /** 非当前行最大缩到 0.94：再小字就看不清了。 */
        const val SCALE_RATE = 0.06f
        const val SCALE_EPSILON = 0.004f

        const val SCROLL_BASE_MS = 340f
        const val SCROLL_PER_ROW_MS = 90f
        /** 播放行在中线附近之外也保底这么亮，避免跳转过渡期高亮忽然消失。 */
        const val ACTIVE_MIN_ALPHA = 0.92f
        const val ALPHA_EPSILON = 0.004f

        /** 左右对称的内边距，给右侧 ▶ 让位。 */
        const val SIDE_PAD_DP = 36f

        const val INDICATOR_RADIUS_DP = 16f
        const val INDICATOR_INSET_DP = 18f
        const val INDICATOR_FADE_MS = 160L

        /** 手指离开后，多久没再操作就回到跟随模式。 */
        const val BROWSE_TIMEOUT_MS = 3_000L

        /** 跳转后最多忽略多久的旧进度：网络流 seek 可能要一会儿，但不能永远不信播放器。 */
        const val STALE_PROGRESS_MS = 1500L
    }
}
