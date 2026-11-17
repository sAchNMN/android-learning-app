package com.szc.learning.music.ui.common

import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import coil.load
import com.szc.learning.core.common.music.MusicTrack

/**
 * 封面加载。
 *
 * 本地封面是 `content://media/external/audio/albumart/{albumId}`，
 * 云端是 http 地址 —— Coil 两边都能吃，所以这里不需要分叉。
 *
 * 真正要做的是**兜底**：没有封面时用 [CoverArt] 按曲目哈希生成一张渐变图，
 * 而不是留白或灰色方块。列表里一排灰块是廉价感最大的来源。
 *
 * 圆角交给 ShapeableImageView 自己裁，不再叠一层 transformation，
 * 免得两处圆角半径对不上，看起来像"圆角里还有一层圆角"。
 */
fun ImageView.bindArt(track: MusicTrack, sizePx: Int = 320) {
    val fallback = BitmapDrawable(resources, CoverArt.of(track.key, sizePx))
    val art = track.artUri
    if (art.isNullOrBlank()) {
        setImageDrawable(fallback)
        return
    }
    load(art) {
        placeholder(fallback)
        error(fallback)
        fallback(fallback)
        crossfade(180)
    }
}
