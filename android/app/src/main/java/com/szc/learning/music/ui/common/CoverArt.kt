package com.szc.learning.music.ui.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import kotlin.math.abs

/**
 * 无封面时的兜底封面。
 *
 * 不画灰色方块 —— 一排灰色方块会让整个界面立刻廉价。
 * 这里按「艺人+标题」的哈希从固定色板里取一对颜色做斜向渐变，
 * 同一首歌永远是同一张图（可复现），但不同歌之间又有区分度。
 * 再叠一层左上镜面高光和底部压暗，观感和真封面放一起不违和。
 */
object CoverArt {

    private val palette = intArrayOf(
        0xFFFF8FB1.toInt(),
        0xFF8B7BFF.toInt(),
        0xFF57E0CE.toInt(),
        0xFFFFC46B.toInt(),
        0xFF7AC6FF.toInt(),
        0xFFFF9E80.toInt(),
        0xFFB6A0FF.toInt(),
        0xFF6FE3B8.toInt(),
    )

    private val cache = object : LruCache<String, Bitmap>(32) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun of(seed: String, sizePx: Int): Bitmap {
        val size = sizePx.coerceIn(32, 1024)
        val key = "$seed@$size"
        cache.get(key)?.let { return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val hash = abs(seed.hashCode())
        val first = palette[hash % palette.size]
        val second = palette[(hash / palette.size + 3) % palette.size]
        val s = size.toFloat()

        // 底色：从左上到右下，两色过渡
        paint.shader = LinearGradient(
            0f, 0f, s, s,
            blend(first, second, 0.88f),
            second,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, s, s, paint)

        // 左上高光
        paint.shader = RadialGradient(
            s * 0.22f, s * 0.16f, s * 0.85f,
            withAlpha(Color.WHITE, 0.24f),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, s, s, paint)

        // 底部压暗，给文字留出可读的暗面
        paint.shader = LinearGradient(
            0f, s * 0.55f, 0f, s,
            Color.TRANSPARENT,
            withAlpha(Color.BLACK, 0.22f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, s * 0.55f, s, s, paint)

        // 一道斜向玻璃反光，让"无封面"看起来像有设计，而不是缺图
        paint.shader = LinearGradient(
            0f, s, s, 0f,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.WHITE, 0.10f), Color.TRANSPARENT),
            floatArrayOf(0.34f, 0.5f, 0.66f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, s, s, paint)
        paint.shader = null

        cache.put(key, bitmap)
        return bitmap
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (Color.red(from) * (1 - ratio) + Color.red(to) * ratio).toInt()
        val g = (Color.green(from) * (1 - ratio) + Color.green(to) * ratio).toInt()
        val b = (Color.blue(from) * (1 - ratio) + Color.blue(to) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or (((alpha * 255f).toInt() and 0xFF) shl 24)
}
