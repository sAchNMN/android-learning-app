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
 * 这里按「艺人+标题」的哈希从主题色板里取一对颜色做斜向渐变，
 * 同一首歌永远是同一张图（可复现），不同歌之间又有区分度。
 * 再叠一层左上镜面高光和底部压暗，观感和真封面放一起不违和。
 *
 * 色板来自当前主题的 4 个色斑色（不是写死的彩虹）：换主题时兜底封面跟着变，
 * 页面上的大块颜色才始终是"同一个 App"。这是配色里最容易翻车的地方 ——
 * 内容色和品牌色各走一套，界面就会像四个 App 的截图拼起来。
 */
object CoverArt {

    /** 主题色板。默认值是极光主题的近似值，[configure] 会用真实主题覆盖。 */
    private var palette = intArrayOf(
        0xFFAAB2E4.toInt(),
        0xFFC69BD8.toInt(),
        0xFF7FB3E8.toInt(),
        0xFF7DC4C6.toInt(),
        0xFFB8AEE0.toInt(),
        0xFFD4A6CC.toInt(),
        0xFF95BCE8.toInt(),
        0xFF93CBC9.toInt(),
    )

    /**
     * 兜底封面缓存。
     *
     * `sizeOf` 返回的是 KB，所以上限也必须是 KB —— 这里曾经写成 `LruCache(32)`，
     * 相当于 32KB 的容量：一张 168px 封面就有 110KB，put 进去立刻被自己挤掉，
     * 缓存等于从来没有命中过。结果是每绑一行就在主线程现画一张渐变位图，
     * 列表一重绑就掉帧。上限取 6MB，够放几十张封面。
     */
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /**
     * 跟着当前主题重算色板。换主题时要连缓存一起清，否则旧图还是旧颜色。
     * 由 ThemeManager.applyPalette 调用，所以每个 Activity 进来都会拿到对的颜色。
     *
     * 整块色板只围绕品牌色相 ±60° 展开 —— 不这么做的后果就是上面截图里
     * 那种"紫色按钮配一屏青色封面"的拼盘感。
     */
    fun configure(accent: Int, dark: Boolean) {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        // 色相只敢动 ±8°：封面的面积远大于按钮，色相一散就成拼盘。
        // 区分度靠明度做，不靠色相 —— 这也是高级感的主要来源。
        val offsets = floatArrayOf(-8f, -4f, 0f, 4f, 8f, -2f, 2f, 6f)
        // 深色下封面要压到中低明度：深底上放接近白的大色块会直接抢掉整个界面
        val top = if (dark) 0.56f else 0.93f
        val step = if (dark) 0.045f else 0.06f
        val saturationFactor = if (dark) 0.52f else 0.40f
        palette = IntArray(offsets.size) { index ->
            // 封面是"大色块"，彩度必须比按钮低得多，否则整页都在喊
            val saturation = (hsv[1] * saturationFactor).coerceIn(0.12f, 0.26f)
            val value = (top - step * (index % 4)).coerceIn(0.30f, 0.93f)
            val hue = ((hsv[0] + offsets[index]) + 360f) % 360f
            Color.HSVToColor(floatArrayOf(hue, saturation, value))
        }
        cache.evictAll()
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

        // 底色：两色过渡。方向按哈希翻一次 —— 同一色族里靠"光从哪来"制造区分度，
        // 比换色相高级，也不会把页面拆成好几个配色
        val flip = (hash / 7) % 2 == 0
        paint.shader = if (flip) {
            LinearGradient(0f, 0f, s, s, blend(first, second, 0.88f), second, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(s, 0f, 0f, s, blend(first, second, 0.88f), second, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, s, s, paint)

        // 左上高光（方向跟着渐变一起翻，看起来才像同一束光）
        paint.shader = RadialGradient(
            if (flip) s * 0.22f else s * 0.78f, s * 0.16f, s * 0.85f,
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

    /** 缓存上限，单位 KB（和 [cache] 的 sizeOf 口径一致）。 */
    private const val CACHE_KB = 6 * 1024
}
