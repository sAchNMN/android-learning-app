package com.szc.learning.core.common

import android.content.Context
import android.view.View
import android.widget.Toast
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

fun Context.toast(@StringRes resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

fun Context.toast(message: CharSequence?) {
    if (!message.isNullOrBlank()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
