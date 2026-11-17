package com.szc.learning.music.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.szc.learning.R

/**
 * 分段控件（歌曲 / 专辑 / 艺人）。
 *
 * 和 tab 栏是同一套手感的两个尺度：这里胶囊是"实心白"，因为它压在一个浅玻璃轨道上，
 * 需要更强的对比才能看出当前选中；tab 栏那边胶囊是半透明，因为它压在整个页面上。
 *
 * GlassView 继承自 FrameLayout，所以内部再放一个 LinearLayout 承载选项，
 * 不能直接给 GlassView 设 orientation —— FrameLayout 没有这个属性。
 */
class GlassSegmented @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    var onSelected: ((Int) -> Unit)? = null

    private val row = LinearLayout(context)
    private val labels = mutableListOf<TextView>()
    private var selectedIndex = 0

    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(android.graphics.Color.WHITE, 0.95f)
    }
    private val capsuleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(android.graphics.Color.BLACK, 0.06f)
    }
    private val capsuleShape = Path()
    private val capsuleRect = RectF()
    private var animator: ValueAnimator? = null

    init {
        corner = context.dp(19f)
        topAlpha = 0.50f
        bottomAlpha = 0.34f
        rimAlpha = 0.55f
        elevation = context.dp(3f)

        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setItems(items: List<String>) {
        row.removeAllViews()
        labels.clear()
        items.forEachIndexed { index, label ->
            val view = TextView(context).apply {
                text = label
                textSize = 13f
                includeFontPadding = false
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = -0.005f
                isClickable = true
                setOnClickListener {
                    select(index)
                    onSelected?.invoke(index)
                }
            }
            row.addView(
                view,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
            labels += view
        }
        applySelection(animate = false)
    }

    fun select(index: Int) {
        if (index !in labels.indices || index == selectedIndex) return
        selectedIndex = index
        applySelection(animate = true)
    }

    private fun applySelection(animate: Boolean) {
        val active = ContextCompat.getColor(context, R.color.ink)
        val idle = withAlpha(active, 0.45f)
        labels.forEachIndexed { index, label ->
            label.setTextColor(if (index == selectedIndex) active else idle)
        }
        moveCapsule(animate)
    }

    private fun moveCapsule(animate: Boolean) {
        if (labels.isEmpty() || width == 0) return
        val widthPerItem = width.toFloat() / labels.size
        val inset = context.dp(4f)
        val targetLeft = selectedIndex * widthPerItem + inset
        val targetWidth = widthPerItem - inset * 2

        animator?.cancel()
        if (!animate) {
            buildCapsule(targetLeft, targetWidth)
            invalidate()
            return
        }

        val startLeft = capsuleRect.left
        val startWidth = capsuleRect.width()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                buildCapsule(
                    startLeft + (targetLeft - startLeft) * t,
                    startWidth + (targetWidth - startWidth) * t,
                )
                invalidate()
            }
            start()
        }
    }

    private fun buildCapsule(left: Float, widthPx: Float) {
        val top = context.dp(4f)
        val bottom = height - context.dp(4f)
        capsuleRect.set(left, top, left + widthPx, bottom)
        capsuleShape.reset()
        val r = capsuleRect.height() / 2f
        capsuleShape.addRoundRect(capsuleRect, r, r, Path.Direction.CW)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        animator?.cancel()
        if (labels.isNotEmpty()) {
            val widthPerItem = w.toFloat() / labels.size
            val inset = context.dp(4f)
            buildCapsule(selectedIndex * widthPerItem + inset, widthPerItem - inset * 2)
        }
    }

    override fun onGlassDrawn(canvas: Canvas) {
        if (capsuleRect.width() <= 0f) return
        val r = capsuleRect.height() / 2f
        canvas.drawRoundRect(
            capsuleRect.left,
            capsuleRect.top + context.dp(1.5f),
            capsuleRect.right,
            capsuleRect.bottom + context.dp(1.5f),
            r,
            r,
            capsuleShadow,
        )
        canvas.drawPath(capsuleShape, capsulePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
