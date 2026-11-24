package com.szc.learning.music.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import com.szc.learning.core.common.music.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 播放页背景的一对颜色（纵向渐变的上、下两端）。
 *
 * 两个颜色都已经压暗到「白字能读」的程度，调用方拿到就直接用，不用再算对比度。
 */
data class CoverColors(val top: Int, val bottom: Int)

/**
 * 从封面取主色，压暗成深色背景。
 *
 * 为什么不用 `Palette.getDarkVibrantColor()` 直接拿来当背景：Palette 返回的是
 * 「封面上真实的那块颜色」，饱和度高、明度不可控。一张亮黄封面能取到
 * `#FFE94A`，白字压上去直接看不见。这里统一过一遍 HSL 夹取 + 对比度兜底，
 * 所以**任何**封面（纯白、纯黑、荧光色）出来的背景都保证可读。
 *
 * 颜色运算全部手写，不用 `android.graphics.Color`：它是 android.jar 的桩实现，
 * 在单元测试里调用会抛 "Stub!"，而这条压暗规则是最需要测试的部分。
 */
object CoverPalette {

    /**
     * 取色失败 / 无封面时的兜底。
     *
     * 取的是极光主题深色底的近似值：比纯黑略亮，和 `auDarkCanvas` 放一起不会出现
     * 一块死黑。故意写成常量而不是读主题 —— 背景是「演出舞台」，深色是刻意的，
     * 换浅色主题也不该变成白底黑字。
     */
    val Fallback = CoverColors(top = 0xFF141822.toInt(), bottom = 0xFF07090E.toInt())

    /** 压暗后的明度区间。上限 0.28 是「白字能读」的经验值，下限 0.12 是「别糊成纯黑」。 */
    private const val MIN_LIGHTNESS = 0.12f
    private const val MAX_LIGHTNESS = 0.28f

    /** 饱和度上限。封面里的大红大绿压到 0.65 之后仍然是「有色」的，但不再刺眼。 */
    private const val MAX_SATURATION = 0.65f

    /** 白字与背景的最低对比度（WCAG AA 正文标准）。 */
    private const val MIN_CONTRAST = 4.5f

    /** bottom 相对 top 再暗 40%。 */
    private const val BOTTOM_DARKEN = 0.6f

    /**
     * 容量 32 的 LRU，按 [MusicTrack.key] 存。
     *
     * 手写而不是用 `android.util.LruCache`：后者在 JVM 单测里是 android.jar 的桩，
     * 构造时就抛 "Stub!"，会把同文件里纯逻辑的压暗规则一起测不了。
     * 每项只有两个 Int，LinkedHashMap 的开销可以忽略。
     */
    private val cache = object : LinkedHashMap<String, CoverColors>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CoverColors>?,
        ): Boolean = size > CACHE_SIZE
    }

    @Synchronized
    fun cached(key: String): CoverColors? = cache[key]

    @Synchronized
    private fun remember(key: String, colors: CoverColors) {
        cache[key] = colors
    }

    /**
     * 取封面小图并算颜色，结果按 [MusicTrack.key] 缓存。
     *
     * 走的是全局 ImageLoader（
     * 因此本地 `content://` 的 ID3 封面和在线 `http://` 封面是同一条路径）。
     * 请求只要 96px：取色不需要细节，96px 已经够 Palette 分出主色，解码也便宜。
     */
    suspend fun load(context: Context, track: MusicTrack): CoverColors {
        cached(track.key)?.let { return it }
        val bitmap = loadBitmap(context, track) ?: return Fallback
        val colors = extract(bitmap)
        remember(track.key, colors)
        return colors
    }

    private suspend fun loadBitmap(context: Context, track: MusicTrack): Bitmap? {
        val art = track.artUri
        if (art.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(art)
                    .allowHardware(false)
                    .size(SAMPLE_PX)
                    .build()
                val result = context.imageLoader.execute(request)
                (result.drawable as? BitmapDrawable)?.bitmap
            }.getOrNull()
        }
    }

    /** 从 Bitmap 取色。在后台线程调用（Palette 的生成是同步 CPU 活）。 */
    suspend fun extract(bitmap: Bitmap): CoverColors = withContext(Dispatchers.Default) {
        val sample = if (max(bitmap.width, bitmap.height) > SAMPLE_PX) {
            val scale = SAMPLE_PX.toFloat() / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val palette = Palette.from(sample)
            .maximumColorCount(16)
            .generate()
        val swatch = palette.darkVibrantSwatch
            ?: palette.vibrantSwatch
            ?: palette.dominantSwatch
        if (swatch == null) Fallback else tone(swatch.rgb)
    }

    /**
     * 把一个原始颜色压成一对可用的背景色。
     *
     * 顺序很重要：先夹 HSL，再逐个压明度直到白字对比度达标。
     * 只夹 HSL 是不够的 —— 明黄在 L=0.28 / S=0.65 时亮度仍然偏高，
     * 所以最后必须有一道按真实相对亮度算的兜底。
     */
    internal fun tone(color: Int): CoverColors {
        val hsl = rgbToHsl(color)
        val hue = hsl[0]
        val saturation = min(hsl[1], MAX_SATURATION)
        val lightness = hsl[2].coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        return CoverColors(
            top = toneColor(hue, saturation, lightness),
            bottom = toneColor(hue, saturation, lightness * BOTTOM_DARKEN),
        )
    }

    /**
     * 把一组 HSL 压成「白字可读」的颜色。
     *
     * 两条约束同时验证：白字对比度 ≥ 4.5、饱和度 ≤ 0.65。
     * 必须验证而不是只夹浮点值：8 位色深里 0.65 的饱和度落到 RGB 再算回来是 0.6512，
     * 只夹 HSL 的写法在边界上会漏出去。
     */
    private fun toneColor(hue: Float, saturation: Float, lightness: Float): Int {
        var s = saturation
        var l = lightness.coerceAtLeast(0.02f)
        var color = hslToColor(hue, s, l)
        var guard = 0
        while (guard < 24) {
            val tooLight = contrastWithWhite(color) < MIN_CONTRAST
            val tooSaturated = saturationOf(color) > MAX_SATURATION
            if (!tooLight && !tooSaturated) break
            if (tooLight) {
                l = (l - 0.03f).coerceAtLeast(0.02f)
            } else {
                s = (s - 0.02f).coerceAtLeast(0f)
            }
            color = hslToColor(hue, s, l)
            guard++
        }
        return color or ALPHA_OPAQUE
    }

    /** WCAG 相对亮度对比度（对白色）。4.5 是可读的分界线。 */
    internal fun contrastWithWhite(color: Int): Float {
        val l = relativeLuminance(color)
        return (1.0f + 0.05f) / (l + 0.05f)
    }

    internal fun lightnessOf(color: Int): Float = rgbToHsl(color)[2]

    internal fun saturationOf(color: Int): Float = rgbToHsl(color)[1]

    private fun relativeLuminance(color: Int): Float {
        val r = linearize(red(color) / 255f)
        val g = linearize(green(color) / 255f)
        val b = linearize(blue(color) / 255f)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun linearize(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)

    // ---------------------------------------------------------------- 颜色工具

    private fun red(color: Int): Int = (color shr 16) and 0xFF

    private fun green(color: Int): Int = (color shr 8) and 0xFF

    private fun blue(color: Int): Int = color and 0xFF

    /** 返回 [h(0~360), s(0~1), l(0~1)]。 */
    internal fun rgbToHsl(color: Int): FloatArray {
        val r = red(color) / 255f
        val g = green(color) / 255f
        val b = blue(color) / 255f
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val delta = max - min
        val lightness = (max + min) / 2f
        if (delta < 1e-6f) return floatArrayOf(0f, 0f, lightness)
        val saturation = if (lightness > 0.5f) {
            delta / (2f - max - min)
        } else {
            delta / (max + min)
        }
        val hue = when (max) {
            r -> 60f * (((g - b) / delta) % 6f)
            g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        return floatArrayOf((hue + 360f) % 360f, saturation, lightness)
    }

    internal fun hslToColor(hue: Float, saturation: Float, lightness: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - abs(2f * l - 1f)) * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return pack(r1 + m, g1 + m, b1 + m)
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        // 四舍五入而不是截断：截断会让 0.4*255=101.99999 变成 101，
        // HSL→RGB→HSL 往返一次色相/饱和度就偏了，压暗区间的断言也会擦边失败
        val ri = (r * 255f + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g * 255f + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    /** 取色样本边长：再大对主色没有帮助，只是多花解码时间。 */
    private const val SAMPLE_PX = 96
    private const val ALPHA_OPAQUE = 0xFF000000.toInt()
    private const val CACHE_SIZE = 32
}
