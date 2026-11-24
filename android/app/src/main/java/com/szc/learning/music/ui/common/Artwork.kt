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
    // 已经量过就用真实宽度：调用方传的 sizePx 都是估的，普遍偏大（列表行 48dp 却画 220px），
    // 白白多占 2~3 倍显存和绘制时间。还没量到时才退回调用方的估值。
    val target = if (width > 0) width else sizePx
    val fallback = BitmapDrawable(resources, CoverArt.of(track.key, target))
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

/**
 * 背景专用封面：只要一张很小的图，放大铺满。
 *
 * 不能直接用 [bindArt] —— 它会拿 View 的实测宽度当请求尺寸，而背景 View 是全屏的，
 * 等于请求一张 1080px 的图。背景要的是「160px 放大」这种自带柔化的效果：
 * 小图由 GPU 双线性放大，天然就是一坨柔光，低版本和高版本看起来一模一样，
 * 也不再需要 RenderEffect 去对 1400px 大图做 GPU 模糊（那是播放页最贵的一笔）。
 *
 * [alpha] 故意压得很低：背景只是给渐变垫一层「这歌的质感」，颜色由渐变层主导。
 */
fun ImageView.bindBackdropArt(track: MusicTrack, sizePx: Int = 160, alpha: Float = 0.35f) {
    this.alpha = alpha
    val fallback = BitmapDrawable(resources, CoverArt.of(track.key, sizePx))
    val art = track.artUri
    if (art.isNullOrBlank()) {
        setImageDrawable(fallback)
        return
    }
    load(art) {
        // 显式给尺寸，绕开 bindArt 里「按 View 宽度」那一套
        size(sizePx)
        placeholder(fallback)
        error(fallback)
        fallback(fallback)
        crossfade(220)
    }
}
