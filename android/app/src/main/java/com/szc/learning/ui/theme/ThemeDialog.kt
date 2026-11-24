package com.szc.learning.ui.theme

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.databinding.DialogThemeBinding
import com.szc.learning.databinding.ItemThemeOptionBinding
import com.szc.learning.music.ui.widget.dp

/**
 * 外观面板：亮暗三档 + 5 套主题。
 *
 * 亮暗改完交给 AppCompatDelegate —— 它会重建所有 Activity，所以这里不用手动 recreate；
 * 换主题是"同一亮暗下换配色"，AppCompat 不会动，必须自己 recreate。
 *
 * 色样取的是"被选主题自己的色斑色"，所以在当前主题下要临时套一层
 * ContextThemeWrapper 才取得到，见 [ThemeManager.colorOf]。
 */
class ThemeDialog(private val activity: AppCompatActivity) : BottomSheetDialog(activity) {

    private lateinit var binding: DialogThemeBinding

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogThemeBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        // 弹层自己的白色圆角背景会盖住 bg_dialog，去掉它
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        // 手势条下面要给内容留位置，否则最后一行"墨色"会被切掉
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bottom = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars(),
            ).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + 12)
            insets
        }
        buildModeRow()
        buildThemeList()
    }

    private fun buildModeRow() {
        val row = binding.themeMode
        row.removeAllViews()
        val current = ThemeManager.mode(context)
        AuroraMode.entries.forEach { mode ->
            val label = TextView(context).apply {
                text = context.getString(mode.labelRes)
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                letterSpacing = -0.005f
                setOnClickListener { pickMode(mode) }
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
            applyModeLook(label, selected = mode == current)
        }
    }

    private fun applyModeLook(label: TextView, selected: Boolean) {
        label.background = if (selected) {
            context.getDrawable(R.drawable.bg_segment_thumb)
        } else {
            null
        }
        label.setTextColor(
            context.themeColor(if (selected) R.attr.auInk else R.attr.auInkSecondary),
        )
        label.typeface = Typeface.create(
            "sans-serif-medium",
            if (selected) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun buildThemeList() {
        val list = binding.themeList
        list.removeAllViews()
        val current = ThemeManager.palette(context)
        AuroraTheme.entries.forEach { theme ->
            val row = ItemThemeOptionBinding.inflate(LayoutInflater.from(context), list, false)
            val selected = theme == current
            row.themeName.setText(theme.labelRes)
            row.themeSwatch.background = swatchOf(theme)
            row.themeCheck.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            row.themeCheck.imageTintList =
                ColorStateList.valueOf(context.themeColor(R.attr.auAccent))
            row.root.setOnClickListener { pickTheme(theme) }
            list.addView(row.root)
        }
    }

    /** 一条小极光：4 个色斑色从左到右渐变。 */
    private fun swatchOf(theme: AuroraTheme): GradientDrawable {
        val colors = theme.swatchAttrs.map { ThemeManager.colorOf(context, theme, it) }.toIntArray()
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = context.dp(13f)
            setStroke(
                context.dp(1f).toInt(),
                ThemeManager.colorOf(context, theme, R.attr.auHairline),
            )
        }
    }

    private fun pickMode(mode: AuroraMode) {
        if (mode == ThemeManager.mode(context)) return
        ThemeManager.selectMode(activity, mode)
        dismiss()
    }

    private fun pickTheme(theme: AuroraTheme) {
        if (theme == ThemeManager.palette(context)) return
        ThemeManager.selectPalette(activity, theme)
        dismiss()
        activity.recreate()
    }

    companion object {
        fun show(activity: AppCompatActivity) {
            ThemeDialog(activity).show()
        }
    }
}