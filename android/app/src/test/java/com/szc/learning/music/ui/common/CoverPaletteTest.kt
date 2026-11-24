package com.szc.learning.music.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压暗规则的单元测试。
 *
 * 这条规则是「白色标题/歌手/歌词在任何封面上都能读」的唯一保障，
 * 所以覆盖三个极端输入：纯白（最亮的封面）、纯黑（最暗的封面）、高饱和红
 * （最容易在压暗后仍然过亮的色相）。
 *
 * 只测纯 Kotlin 的颜色运算，不碰 Bitmap / Palette —— 那些在 JVM 单测里是桩。
 */
class CoverPaletteTest {

    @Test
    fun `纯白封面被压到可读区间`() {
        assertTone(0xFFFFFFFF.toInt())
    }

    @Test
    fun `纯黑封面被提到可读区间而不是纯黑`() {
        assertTone(0xFF000000.toInt())
        val colors = CoverPalette.tone(0xFF000000.toInt())
        // 纯黑提亮到 0.12 之后仍然很暗，但绝不能是 #000000 —— 那会让背景和 scrim 糊成一块
        assertTrue("top 不应是纯黑", colors.top != 0xFF000000.toInt())
    }

    @Test
    fun `高饱和荧光红也不会让白字看不清`() {
        assertTone(0xFFFF0000.toInt())
    }

    @Test
    fun `明黄封面同样满足对比度要求`() {
        // 黄色是「HSL 夹取之后仍然偏亮」的典型：只夹 HSL 不做对比度兜底会漏掉它
        assertTone(0xFFFFE94A.toInt())
    }

    @Test
    fun `bottom 比 top 更暗`() {
        val colors = CoverPalette.tone(0xFF3366CC.toInt())
        val topL = relativeLuminanceOf(colors.top)
        val bottomL = relativeLuminanceOf(colors.bottom)
        assertTrue("bottom($bottomL) 应暗于 top($topL)", bottomL < topL)
    }

    @Test
    fun `饱和度压到上限以内`() {
        val colors = CoverPalette.tone(0xFF00FF00.toInt())
        assertTrue(CoverPalette.saturationOf(colors.top) <= 0.65f + 1e-3f)
        assertTrue(CoverPalette.saturationOf(colors.bottom) <= 0.65f + 1e-3f)
    }

    private fun assertTone(input: Int) {
        val colors = CoverPalette.tone(input)
        listOf("top" to colors.top, "bottom" to colors.bottom).forEach { (name, color) ->
            val lightness = CoverPalette.lightnessOf(color)
            val contrast = CoverPalette.contrastWithWhite(color)
            assertTrue(
                "$name lightness=$lightness 应在 0.02~0.29 之间（压暗区间 + 兜底余量）",
                lightness in 0.02f..0.29f,
            )
            assertTrue(
                "$name 对比度=$contrast 应 ≥ 4.5（输入 #${Integer.toHexString(input)}）",
                contrast >= 4.5f,
            )
            assertTrue(
                "$name 饱和度=${CoverPalette.saturationOf(color)} 应 ≤ 0.65",
                CoverPalette.saturationOf(color) <= 0.65f + 1e-3f,
            )
        }
    }

    private fun relativeLuminanceOf(color: Int): Float {
        // contrast = 1.05 / (L + 0.05) → L = 1.05 / contrast - 0.05
        return 1.05f / CoverPalette.contrastWithWhite(color) - 0.05f
    }

    @Test
    fun `HSL 往返不丢色相`() {
        val hsl = CoverPalette.rgbToHsl(0xFF3366CC.toInt())
        val back = CoverPalette.hslToColor(hsl[0], hsl[1], hsl[2])
        assertEquals(0xFF3366CC.toInt(), back)
    }
}
