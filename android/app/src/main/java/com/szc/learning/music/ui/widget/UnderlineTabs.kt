package com.szc.learning.music.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor

/**
 * 下划线分段导航（歌曲 / 歌手 / 专辑 / 文件夹）。
 *
 * 参考图里的顶部 tab 有两层信息：字重和一条短蓝线。
 * 这条线画在文字下方 6dp、宽 20dp，切页时横向滑过去 ——
 * 动画本身比线更有用，视线能跟上"从哪一栏切到哪一栏"。
 */
class UnderlineTabs @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var onSelected: ((Int) -> Unit)? = null

    private val row = LinearLayout(context)
    private val labels = mutableListOf<TextView>()
    private val indicator = View(context)
    private var selectedIndex = 0
    private var animator: ValueAnimator? = null
    private var indicatorLeft = 0f

    init {
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        indicator.background = GradientDrawable().apply {
            cornerRadius = context.dp(2f)
            setColor(context.themeColor(R.attr.auAccent))
        }
        addView(indicator, LayoutParams(context.dp(20f).toInt(), context.dp(3f).toInt()))
    }

    fun setItems(items: List<String>) {
        row.removeAllViews()
        labels.clear()
        items.forEachIndexed { index, text ->
            val label = TextView(context).apply {
                this.text = text
                textSize = 15f
                includeFontPadding = false
                gravity = Gravity.CENTER
                letterSpacing = -0.005f
                setOnClickListener { select(index, notify = true) }
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            }
            row.addView(label)
            labels += label
        }
        applySelection()
        post { moveIndicator(selectedIndex, animate = false) }
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index !in labels.indices) return
        val changed = index != selectedIndex
        selectedIndex = index
        applySelection()
        moveIndicator(index, animate = changed)
        if (notify) onSelected?.invoke(index)
    }

    private fun applySelection() {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            label.setTextColor(
                context.themeColor(if (selected) R.attr.auInk else R.attr.auInkSecondary),
            )
            label.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
        }
    }

    private fun moveIndicator(index: Int, animate: Boolean) {
        if (labels.isEmpty() || width == 0) return
        val itemWidth = width.toFloat() / labels.size
        val target = index * itemWidth + (itemWidth - context.dp(20f)) / 2f
        if (!animate) {
            animator?.cancel()
            indicatorLeft = target
            applyIndicator()
            return
        }
        animator?.cancel()
        val start = indicatorLeft
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                indicatorLeft = start + (target - start) * t
                applyIndicator()
            }
            start()
        }
    }

    private fun applyIndicator() {
        indicator.translationX = indicatorLeft
        indicator.translationY = height - context.dp(1f) - context.dp(3f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        moveIndicator(selectedIndex, animate = false)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}