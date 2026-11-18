package com.szc.learning.ui.theme

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.szc.learning.R
import com.szc.learning.music.ui.common.CoverArt

/**
 * 5 套主题。每套是一个完整的调色板（亮/暗各一份），不是"只换一个强调色"。
 *
 * 为什么把中性色也一起换：只换强调色而中性色不动的话，暖色品牌的界面会显得脏，
 * 冷色品牌配暖灰会发灰。所以每套主题的中性色都带自己的色相，冷暖跟品牌色走。
 */
enum class AuroraTheme(
    val key: String,
    @StringRes val labelRes: Int,
    @StringRes val noteRes: Int,
    @StyleRes val styleRes: Int,
    /** 选择器里的色样：4 个色斑色，画成一条小极光 */
    val swatchAttrs: IntArray,
) {
    AURORA(
        "aurora", R.string.theme_aurora, R.string.theme_aurora_note,
        R.style.Theme_Aurora,
        intArrayOf(R.attr.auAccent, R.attr.auBlobA, R.attr.auBlobC),
    ),
    EMBER(
        "ember", R.string.theme_ember, R.string.theme_ember_note,
        R.style.Theme_Aurora_Ember,
        intArrayOf(R.attr.auAccent, R.attr.auBlobA, R.attr.auBlobC),
    ),
    SAGE(
        "sage", R.string.theme_sage, R.string.theme_sage_note,
        R.style.Theme_Aurora_Sage,
        intArrayOf(R.attr.auAccent, R.attr.auBlobA, R.attr.auBlobC),
    ),
    COBALT(
        "cobalt", R.string.theme_cobalt, R.string.theme_cobalt_note,
        R.style.Theme_Aurora_Cobalt,
        intArrayOf(R.attr.auAccent, R.attr.auBlobA, R.attr.auBlobC),
    ),
    NOIR(
        "noir", R.string.theme_noir, R.string.theme_noir_note,
        R.style.Theme_Aurora_Noir,
        intArrayOf(R.attr.auAccent, R.attr.auBlobA, R.attr.auBlobC),
    );

    companion object {
        fun of(key: String?): AuroraTheme = entries.firstOrNull { it.key == key } ?: AURORA
    }
}

/** 明暗三档。跟随系统是默认，因为大多数人的系统里本来就有这个偏好。 */
enum class AuroraMode(
    val key: String,
    @StringRes val labelRes: Int,
    val delegate: Int,
) {
    SYSTEM("system", R.string.theme_mode_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", R.string.theme_mode_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.theme_mode_dark, AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun of(key: String?): AuroraMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 主题的唯一入口。规则：
 *
 * 1. Activity 必须在 `super.onCreate` / `setContentView` 之前调 [applyPalette]，
 *    晚了窗口已经按清单里的默认主题创建好了，会闪一下旧配色。
 * 2. 组件里取颜色一律用 [themeColor]，不要出现 `R.color.*` —— 颜色只在
 *    colors_theme_*.xml 里出现一次，组件只认语义。
 * 3. 明暗交给 AppCompatDelegate，它会自己重建 Activity；
 *    换主题（同一明暗下）需要手动 `recreate()`。
 */
object ThemeManager {

    private const val PREFS = "aurora.appearance"
    private const val KEY_PALETTE = "palette"
    private const val KEY_MODE = "mode"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun palette(context: Context): AuroraTheme =
        AuroraTheme.of(prefs(context).getString(KEY_PALETTE, null))

    fun mode(context: Context): AuroraMode =
        AuroraMode.of(prefs(context).getString(KEY_MODE, null))

    fun selectPalette(context: Context, theme: AuroraTheme) {
        prefs(context).edit().putString(KEY_PALETTE, theme.key).apply()
    }

    fun selectMode(context: Context, mode: AuroraMode) {
        prefs(context).edit().putString(KEY_MODE, mode.key).apply()
        AppCompatDelegate.setDefaultNightMode(mode.delegate)
    }

    /** 在 `super.onCreate` 之前调用。 */
    fun applyPalette(activity: AppCompatActivity) {
        activity.setTheme(palette(activity).styleRes)
        // 兜底封面用的是品牌色相，换了主题必须跟着换，
        // 否则页面上的大色块还是上一个主题的颜色
        val night = activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        CoverArt.configure(activity.themeColor(R.attr.auAccent), night)
    }

    /** 在 Application.onCreate 里调用，让冷启动第一次就落在正确的明暗上。 */
    fun applyModeOnLaunch(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context).delegate)
    }

    /**
     * 读"另一个主题"的颜色 —— 用在选择器里画色样：
     * 当前主题下要能看到别的主题长什么样，所以得临时套一层 ContextThemeWrapper。
     */
    @ColorInt
    fun colorOf(context: Context, theme: AuroraTheme, @AttrRes attr: Int): Int {
        val wrapper = androidx.appcompat.view.ContextThemeWrapper(context, theme.styleRes)
        return wrapper.themeColor(attr)
    }
}

/** 当前主题下的语义色。所有自绘控件都走这里。 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attr, value, true)) return 0
    return if (value.resourceId != 0) {
        ContextCompat.getColor(this, value.resourceId)
    } else {
        value.data
    }
}

@ColorInt
fun View.themeColor(@AttrRes attr: Int): Int = context.themeColor(attr)

/** 需要 ColorStateList 的地方（ImageView tint 之类）用这个。 */
fun Context.themeColors(@AttrRes attr: Int): ColorStateList =
    ColorStateList.valueOf(themeColor(attr))