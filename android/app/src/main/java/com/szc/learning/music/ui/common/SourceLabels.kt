package com.szc.learning.music.ui.common

import android.content.Context
import com.szc.learning.R

/**
 * 音源标识 → 展示名。
 *
 * 平台名是专有名词，但不硬编码在代码里：一是统一进 strings.xml，
 * 二是以后加汽水/Spotify 只改这一处。
 */
fun sourceLabel(context: Context, key: String): String = when (key) {
    "netease" -> context.getString(R.string.source_netease)
    "kugou" -> context.getString(R.string.source_kugou)
    "qq" -> context.getString(R.string.source_qq)
    "kuwo" -> context.getString(R.string.source_kuwo)
    else -> key.ifBlank { context.getString(R.string.source_other) }
}

/** 列表里的短标签，只有 3 个字，给曲名右边的小胶囊用。 */
fun sourceShortLabel(context: Context, key: String): String = when (key) {
    "netease" -> context.getString(R.string.source_short_netease)
    "kugou" -> context.getString(R.string.source_short_kugou)
    "qq" -> context.getString(R.string.source_short_qq)
    "kuwo" -> context.getString(R.string.source_short_kuwo)
    else -> context.getString(R.string.source_other)
}