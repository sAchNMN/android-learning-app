package com.szc.learning.music.ui.glass

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import com.szc.learning.R

/**
 * 玻璃材质只有两种，对应两条不同的渲染路径：
 *
 * - [LIQUID]：背后内容录进 GPU，先轻度模糊，再由 AGSL shader 在边缘做**真正的折射**，
 *   并把 RGB 分开取样做色散。染色薄，靠折射和镜面高光找"玻璃"的感觉。
 * - [BLUR]：同一份 GPU 纹理，但只做重度高斯模糊，不做折射、不做高光 ——
 *   背后内容彻底化开，最接近毛玻璃。
 *
 * 两者共用 [GlassSpec.frost]：磨砂程度。液态下它同时调模糊半径和折射强度，
 * 高斯模式下只调模糊半径和染色浓度 —— 用户只有一个旋钮，语义要一致。
 */
enum class GlassMode(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int,
) {
    LIQUID("liquid", R.string.glass_mode_liquid, R.string.glass_mode_liquid_note),
    BLUR("blur", R.string.glass_mode_blur, R.string.glass_mode_blur_note);

    companion object {
        fun of(key: String?): GlassMode = entries.firstOrNull { it.key == key } ?: LIQUID
    }
}

/** 一份玻璃外观。[frost] 0 = 完全通透，1 = 厚磨砂。 */
data class GlassSpec(
    val mode: GlassMode,
    val frost: Float,
) {
    fun clamped(): GlassSpec = copy(frost = frost.coerceIn(0f, 1f))

    companion object {
        val DEFAULT = GlassSpec(GlassMode.LIQUID, 0.66f)
    }
}

/**
 * 玻璃设置的唯一入口（与 [com.szc.learning.ui.theme.ThemeManager] 同构）：
 * 组件只读 [spec]，设置面板只写，写完之后所有玻璃控件通过
 * SharedPreferences 监听自动重绘 —— 不需要谁去通知谁。
 */
object GlassSettings {

    private const val PREFS = "aurora.glass"
    private const val KEY_MODE = "mode"
    private const val KEY_FROST = "frost"

    /** 控件要注册监听时拿这个实例（同一进程内是同一个对象）。 */
    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun spec(context: Context): GlassSpec {
        val prefs = preferences(context)
        return GlassSpec(
            mode = GlassMode.of(prefs.getString(KEY_MODE, null)),
            frost = prefs.getFloat(KEY_FROST, GlassSpec.DEFAULT.frost),
        ).clamped()
    }

    fun selectMode(context: Context, mode: GlassMode) {
        preferences(context).edit().putString(KEY_MODE, mode.key).apply()
    }

    fun selectFrost(context: Context, frost: Float) {
        preferences(context).edit().putFloat(KEY_FROST, frost.coerceIn(0f, 1f)).apply()
    }
}