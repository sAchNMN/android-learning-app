package com.szc.learning.music.ui.widget

import android.content.Context
import android.util.TypedValue
import android.view.View

/** dp / sp 换算。全项目只留这一份，避免各页面自己算密度算错。 */
fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun View.dp(value: Float): Float = context.dp(value)

/** 带透明度的颜色。alpha 传 0f~1f，与颜色自身 alpha 相乘。 */
fun withAlpha(color: Int, alpha: Float): Int {
    val baseAlpha = (color ushr 24) and 0xFF
    val a = (baseAlpha * alpha.coerceIn(0f, 1f)).toInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}
