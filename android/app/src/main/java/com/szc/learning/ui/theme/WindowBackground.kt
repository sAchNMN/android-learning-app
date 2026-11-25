package com.szc.learning.ui.theme

import android.app.Activity

/**
 * 去掉窗口背景。
 *
 * 各页面的根布局都是不透明、铺满整屏的 `?attr/auCanvas`，窗口背景在它下面永远
 * 看不见，却每帧都要先被画一遍 —— 推荐 tab 上就是「窗口 + Activity 根 + Fragment 根」
 * 三层同色全屏填充。
 *
 * 只在根布局确实不透明铺满的页面调用；深色页（沉浸播放、播放 tab）保留自己的背景，
 * 不要用这个。
 */
fun Activity.clearWindowBackground() {
    window.setBackgroundDrawable(null)
}
