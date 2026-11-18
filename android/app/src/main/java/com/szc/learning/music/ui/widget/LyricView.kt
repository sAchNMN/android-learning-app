package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.music.data.LyricLine
import com.szc.learning.music.data.LyricSheet
import com.szc.learning.ui.theme.themeColor
import kotlin.math.abs

/**
 * 实时滚动歌词。
 *
 * 当前行始终停在控件垂直中线，靠 `LinearSmoothScroller` 把目标行推到
 * 上内边距处；上下内边距留成半屏，首尾行也能居中。
 *
 * 播放页本身是竖向 ViewPager2，所以这里**不接管上下拖动**：
 * 只用 GestureDetector 识别单击（点歌词跳转），拖动直接让父级翻页。
 */
class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    /** 点击某一行：有时间码就 seek，没有就由页面决定怎么处理。 */
    var onLineClick: ((LyricLine) -> Unit)? = null

    private val accentColor = context.themeColor(R.attr.auAccent)
    private val lineColor = context.themeColor(R.attr.auOnDark)

    private val lines = ArrayList<LyricLine>()
    private var timed = false
    private var placeholder: CharSequence = ""
    private var activeIndex = -1
    private var currentSheet: LyricSheet? = null

    private val lineAdapter = LineAdapter()

    private val taps = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val child = findChildViewUnder(e.x, e.y) ?: return false
                val position = getChildAdapterPosition(child)
                val line = lines.getOrNull(position) ?: return false
                performClick()
                onLineClick?.invoke(line)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean = false
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
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyCenterPadding()
        if (activeIndex >= 0) centerInstantly(activeIndex)
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
        placeholder = text
        lineAdapter.notifyDataSetChanged()
        applyCenterPadding()
    }

    fun updateProgress(positionMs: Long) = updateProgress(positionMs, force = false)

    private fun updateProgress(positionMs: Long, force: Boolean) {
        if (!timed || lines.isEmpty()) return
        val index = indexAt(positionMs)
        if (index == activeIndex && !force) return
        val previous = activeIndex
        activeIndex = index
        refreshVisibleStyles()
        if (index < 0) return
        // 播放中通常是下一句；跨度太大说明用户拖了进度，直接跳，别慢慢滚
        val jump = previous < 0 || abs(index - previous) > 2
        // 当前行字号变化会触发 requestLayout，等这一帧布局完再滚，避免滚动被取消
        post {
            if (activeIndex == index) scrollToLine(index, smooth = !force && !jump)
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
        val scroller = object : LinearSmoothScroller(context) {
            override fun getVerticalSnapPreference(): Int = LinearSmoothScroller.SNAP_TO_START

            /** 默认速度对一两行歌词太快，拉长到 180~480ms，滚动看得见。 */
            override fun calculateTimeForScrolling(dx: Int): Int =
                (abs(dx) * 3).coerceIn(180, 480)
        }
        scroller.targetPosition = index
        manager.startSmoothScroll(scroller)
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
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            // 父级是竖向 ViewPager2：允许它在手指拖动时接管，翻页优先
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        taps.onTouchEvent(e)
        return true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean = false

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

        fun render(position: Int) {
            val line = lines[position]
            text.text = line.text
            val active = position == activeIndex
            val distance = if (activeIndex < 0) Int.MAX_VALUE else abs(position - activeIndex)
            val alpha = when {
                active -> 1f
                distance == 1 -> 0.58f
                distance == 2 -> 0.36f
                else -> 0.22f
            }
            text.setTextColor(withAlpha(if (active) accentColor else lineColor, alpha))
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (active) ACTIVE_SP else IDLE_SP)
            text.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }

        fun renderPlaceholder(value: CharSequence) {
            text.text = value
            text.setTextColor(withAlpha(lineColor, 0.42f))
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, IDLE_SP)
            text.setTypeface(null, Typeface.NORMAL)
        }
    }

    private companion object {
        /** 单行歌词的估算高度，用来算居中内边距；行高变化只是轻微偏心。 */
        const val LINE_HEIGHT_DP = 40f
        const val ACTIVE_SP = 17f
        const val IDLE_SP = 13f
    }
}
