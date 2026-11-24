package com.szc.learning.core.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils

/**
 * 应用内轻提示（HUD 胶囊）。
 *
 * 为什么不用系统 Toast：
 * 1. Toast 是排队播放的。用户 3 秒里连点 10 次，队列里就压了 10 条，
 *    最后一条要十几秒才轮到 —— 那时早就忘了自己点了什么。
 *    这里整个 Activity 只复用一个胶囊：新文案直接顶掉正在显示的那条，动画打断重来。
 * 2. 系统 Toast 的样式由 ROM 决定，跟液态玻璃的视觉对不上，也没法带业务图标。
 *
 * 落地方式：把 TextView 直接塞进 Activity 的 content 容器，不走 WindowManager，
 * 不需要悬浮窗权限，也不会像 Dialog 那样抢焦点。
 */
object HudToast {

    private const val TAG = "aurora.hud"
    private const val SHOW_MS = 1_400L
    private const val IN_MS = 100L
    private const val OUT_MS = 160L

    /**
     * @param iconRes        提示左侧的小图标，0 表示不带；传业务图标能让用户一眼看出"切到哪种模式了"
     * @param bottomMarginDp 距屏幕底部的距离，页面底部有控制条/导航栏时由调用方调大，避免压住按钮
     */
    fun show(
        context: Context,
        message: CharSequence?,
        @DrawableRes iconRes: Int = 0,
        bottomMarginDp: Int = 118,
    ) {
        if (message.isNullOrBlank()) return
        val activity = context.findActivity()
        if (activity == null) {
            // 拿不到 Activity（application context、Service 里）就退回系统 Toast，总比静默好
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            return
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val pill = root.findViewWithTag<View>(TAG) as? Pill
            ?: Pill(activity).also { root.addView(it.view, it.params) }
        pill.update(message, iconRes, bottomMarginDp)
    }

    private fun Context.findActivity(): Activity? {
        var ctx = this
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun Context.themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!theme.resolveAttribute(attr, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    }

    /** 一个 Activity 里只存在一个实例：它既是"当前要显示什么"，也是"怎么打断上一条"。 */
    private class Pill(activity: Activity) {

        private val density = activity.resources.displayMetrics.density

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        )

        val view = TextView(activity).apply {
            tag = TAG
            gravity = Gravity.CENTER
            includeFontPadding = false
            isClickable = false
            isFocusable = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (280 * density).toInt()
            setPadding(dp(16), dp(9), dp(16), dp(9))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            elevation = 6f * density

            // 跟随主题：浅色主题给白胶囊黑字（和深色播放页对比最清楚），深色主题反过来
            val ink = context.themeColor(android.R.attr.textColorPrimary, 0xFF1A1A1A.toInt())
            val lightInk = ColorUtils.calculateLuminance(ink) < 0.5
            setTextColor(ink)
            background = GradientDrawable().apply {
                cornerRadius = 22f * density
                setColor(if (lightInk) 0xF7FFFFFF.toInt() else 0xE6212124.toInt())
                setStroke(dp(1).coerceAtLeast(1), if (lightInk) 0x14000000 else 0x2EFFFFFF)
            }
        }

        private val detach = Runnable {
            (view.parent as? ViewGroup)?.removeView(view)
        }

        private val hide = Runnable {
            view.animate()
                .alpha(0f)
                .translationY(6f * density)
                .setDuration(OUT_MS)
                .start()
            view.postDelayed(detach, OUT_MS + 24L)
        }

        fun update(message: CharSequence, @DrawableRes iconRes: Int, bottomMarginDp: Int) {
            // 打断上一条：停掉未执行的隐藏/移除任务，掐掉正在跑的动画，不留队列
            view.removeCallbacks(hide)
            view.removeCallbacks(detach)
            view.animate().cancel()

            params.bottomMargin = (bottomMarginDp * density).toInt()
            view.layoutParams = params
            view.text = message

            if (iconRes != 0) {
                val size = dp(15)
                val icon = ResourcesCompat.getDrawable(view.resources, iconRes, view.context.theme)
                    ?.mutate()
                    ?.apply {
                        setTint(view.currentTextColor)
                        setBounds(0, 0, size, size)
                    }
                view.setCompoundDrawables(icon, null, null, null)
                view.compoundDrawablePadding = dp(7)
            } else {
                view.setCompoundDrawables(null, null, null, null)
            }

            view.alpha = 0f
            view.translationY = 8f * density
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(IN_MS)
                .start()
            view.postDelayed(hide, SHOW_MS + IN_MS)
        }

        private fun dp(value: Int): Int = (value * density).toInt()
    }
}
