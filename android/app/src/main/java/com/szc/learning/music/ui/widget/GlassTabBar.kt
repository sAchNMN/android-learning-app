package com.szc.learning.music.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.szc.learning.R

/**
 * 悬浮玻璃 tab 栏。
 *
 * 交互上的关键细节是**选中胶囊会滑动**而不是瞬间跳过去：
 * 位移加一段 240ms 的减速动画，视线就能跟上"焦点从哪移到哪"，
 * 这是 iOS 分段控件的标志性手感，也是这个界面里最容易被忽略但最显"贵"的地方。
 *
 * 胶囊画在玻璃之上、图标之下（覆写 onGlassDrawn），所以它看起来是嵌在玻璃里的。
 */
class GlassTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    data class Tab(val iconRes: Int, val label: String)

    var onTabSelected: ((Int) -> Unit)? = null

    private val row = LinearLayout(context)
    private val iconViews = mutableListOf<ImageView>()
    private val labelViews = mutableListOf<TextView>()
    private var tabs: List<Tab> = emptyList()
    private var selectedIndex = 0

    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capsuleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(android.graphics.Color.BLACK, 0.07f)
    }
    private val capsuleRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = withAlpha(android.graphics.Color.WHITE, 0.8f)
    }
    private val capsuleRect = RectF()
    private val capsuleShape = Path()
    private var capsuleLeft = 0f
    private var animator: ValueAnimator? = null

    init {
        corner = context.dp(31f)
        elevation = context.dp(18f)
        // 悬浮在内容之上时玻璃要更"实"一点，但也不能到纯白：
        // 纯白会盖掉极光底，玻璃就退了成一块白板
        topAlpha = 0.66f
        bottomAlpha = 0.36f
        rimAlpha = 0.95f
        sheenAlpha = 0.40f

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setTabs(items: List<Tab>) {
        tabs = items
        row.removeAllViews()
        iconViews.clear()
        labelViews.clear()

        items.forEachIndexed { index, tab ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = tab.label
                setOnClickListener {
                    select(index)
                    onTabSelected?.invoke(index)
                }
            }

            val icon = ImageView(context).apply {
                setImageResource(tab.iconRes)
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(context.dp(22f).toInt(), context.dp(22f).toInt())
            }

            val label = TextView(context).apply {
                text = tab.label
                textSize = 10f
                includeFontPadding = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.02f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = context.dp(3f).toInt() }
            }

            item.addView(icon)
            item.addView(label)
            row.addView(
                item,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            iconViews += icon
            labelViews += label
        }

        applySelection(animate = false)
    }

    fun select(index: Int, notify: Boolean = false) {
        if (index !in tabs.indices || index == selectedIndex) {
            if (notify) onTabSelected?.invoke(index)
            return
        }
        selectedIndex = index
        applySelection(animate = true)
        if (notify) onTabSelected?.invoke(index)
    }

    fun selectedIndex(): Int = selectedIndex

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scrollCapsuleTo(index = selectedIndex, animate = false)
    }

    private fun applySelection(animate: Boolean) {
        val active = ContextCompat.getColor(context, R.color.ink)
        val idle = withAlpha(active, 0.42f)
        iconViews.forEachIndexed { index, icon ->
            val color = if (index == selectedIndex) active else idle
            icon.imageTintList = ColorStateList.valueOf(color)
        }
        labelViews.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) active else idle)
        }
        scrollCapsuleTo(selectedIndex, animate)
    }

    private fun scrollCapsuleTo(index: Int, animate: Boolean) {
        if (tabs.isEmpty() || width == 0) return
        val itemWidth = width.toFloat() / tabs.size
        val inset = context.dp(9f)
        val target = index * itemWidth + inset
        val targetWidth = itemWidth - inset * 2

        if (!animate) {
            animator?.cancel()
            capsuleLeft = target
            buildCapsule(target, targetWidth)
            invalidate()
            return
        }

        animator?.cancel()
        val startLeft = capsuleLeft
        val startWidth = capsuleRect.width()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                capsuleLeft = startLeft + (target - startLeft) * t
                buildCapsule(capsuleLeft, startWidth + (targetWidth - startWidth) * t)
                invalidate()
            }
            start()
        }
    }

    private fun buildCapsule(left: Float, widthPx: Float) {
        val top = context.dp(7f)
        val bottom = height - context.dp(7f)
        capsuleRect.set(left, top, left + widthPx, bottom)
        capsuleShape.reset()
        val r = capsuleRect.height() / 2f
        capsuleShape.addRoundRect(capsuleRect, r, r, Path.Direction.CW)
    }

    override fun onGlassDrawn(canvas: Canvas) {
        if (tabs.isEmpty()) return
        if (capsuleRect.width() <= 0f) {
            scrollCapsuleTo(selectedIndex, animate = false)
        }
        capsulePaint.color = withAlpha(android.graphics.Color.WHITE, 0.98f)
        // 先画一层下移 1.5dp 的软阴影，选中项才有"浮起来"的层级感
        canvas.save()
        canvas.translate(0f, context.dp(1.5f))
        canvas.drawPath(capsuleShape, capsuleShadowPaint)
        canvas.restore()
        canvas.drawPath(capsuleShape, capsulePaint)
        canvas.drawPath(capsuleShape, capsuleRimPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
