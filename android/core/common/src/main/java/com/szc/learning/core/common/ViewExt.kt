package com.szc.learning.core.common

import android.content.Context
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

fun View.show() {
    if (visibility != View.VISIBLE) visibility = View.VISIBLE
}

fun View.hide() {
    if (visibility != View.GONE) visibility = View.GONE
}

fun View.setVisible(visible: Boolean, goneWhenHidden: Boolean = true) {
    visibility = when {
        visible -> View.VISIBLE
        goneWhenHidden -> View.GONE
        else -> View.INVISIBLE
    }
}

/**
 * 轻提示统一走 [HudToast]：系统 Toast 会排队，连点几次要等十几秒才播完，
 * 而且样式跟 ROM 绑定，跟这里的液态玻璃对不上。
 */
fun Context.toast(@StringRes resId: Int, @DrawableRes iconRes: Int = 0) =
    HudToast.show(this, getString(resId), iconRes)

fun Context.toast(message: CharSequence?, @DrawableRes iconRes: Int = 0) =
    HudToast.show(this, message, iconRes)
