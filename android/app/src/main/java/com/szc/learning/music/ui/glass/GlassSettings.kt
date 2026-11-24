package com.szc.learning.music.ui.glass

import android.content.Context
import android.content.SharedPreferences

/**
 * 毛玻璃外观。
 *
 * 只有一个旋钮：[GlassSpec.frost]，0 = 完全通透，1 = 厚磨砂。
 * 它同时决定模糊半径和染色浓度 —— 用户手里只有一个滑块，语义必须一致。
 *
 * 早期还有一个 "liquid" 模式（AGSL 边缘折射 + 色散 + 触摸光斑），已经删掉：
 * 那是拿帧预算换观感，90Hz 面板上滚动时每次采样都顶在 11.1ms 的线上。
 * 现在只有高斯模糊一条路径，代码里不再有分支，也就不可能再被切回去。
 */
data class GlassSpec(
    val frost: Float,
) {
    /**
     * 0 = 最通透，1 = 最厚。两端都给到底，不设下限 ——
     * 面板不透出背后内容，"太透"最多是露出一点底色，不会串字串到没法看，
     * 值不值得是用户自己的判断。染色映射见 [GlassPainter]。
     */
    fun clamped(): GlassSpec = copy(frost = frost.coerceIn(0f, 1f))

    companion object {
        val DEFAULT = GlassSpec(0.62f)
    }
}

/**
 * 玻璃设置的唯一入口（与 [com.szc.learning.ui.theme.ThemeManager] 同构）：
 * 组件只读 [spec]，设置面板只写，写完之后所有玻璃控件通过
 * SharedPreferences 监听自动重绘 —— 不需要谁去通知谁。
 */
object GlassSettings {

    private const val PREFS = "aurora.glass"
    private const val KEY_FROST = "frost"

    /** 控件要注册监听时拿这个实例（同一进程内是同一个对象）。 */
    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun spec(context: Context): GlassSpec {
        val prefs = preferences(context)
        return GlassSpec(
            frost = prefs.getFloat(KEY_FROST, GlassSpec.DEFAULT.frost),
        ).clamped()
    }

    fun selectFrost(context: Context, frost: Float) {
        preferences(context).edit().putFloat(KEY_FROST, frost.coerceIn(0f, 1f)).apply()
    }
}
