package com.szc.learning.ui.main

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.szc.learning.R

/** A single, bounded reveal; no network or loading state is tied to the animation. */
class LaunchAnimationView(context: Context) : View(context) {
    private val logo = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground))
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 0f
    private var animator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.rgb(16, 24, 41))
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun reveal(onFinished: () -> Unit) {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                alpha = ((1f - progress) / .18f).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (parent as? ViewGroup)?.removeView(this@LaunchAnimationView)
                    onFinished()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height * .43f
        val appear = (progress / .5f).coerceIn(0f, 1f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp
        paint.color = Color.argb((55 * (1 - progress)).toInt(), 120, 245, 208)
        canvas.drawCircle(cx, cy, (48 + progress * 72) * dp, paint)
        val size = (170 + 20 * appear) * dp
        logo.alpha = (255 * appear).toInt()
        logo.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        logo.draw(canvas)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((255 * appear).toInt(), 240, 247, 255)
        paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 32 * dp
        canvas.drawText(context.getString(R.string.app_name), cx, cy + 100 * dp, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
