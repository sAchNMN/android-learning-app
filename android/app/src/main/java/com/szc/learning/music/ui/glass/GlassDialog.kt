package com.szc.learning.music.ui.glass

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.databinding.DialogGlassBinding
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.ui.theme.themeColor
import kotlin.math.roundToInt

/**
 * 玻璃材质面板。现在只有一个决定：磨砂到什么程度。
 *
 * 预览用的是真正的 [GlassSurfaceView]，不是一张效果图：
 * 拖动滑块时预览里的玻璃条和主界面的播放栏/底部导航走同一条绘制与采样路径，
 * 所以"预览里看到什么样，退出去就是什么样"。
 *
 * 设置写入 SharedPreferences，主界面所有玻璃控件通过监听自己重绘，
 * 所以这里不需要持有 Activity，也不会出现"改完要重启才生效"。
 */
class GlassDialog(context: Context) : BottomSheetDialog(context) {

    private lateinit var binding: DialogGlassBinding
    private var spec = GlassSpec.DEFAULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DialogGlassBinding.inflate(LayoutInflater.from(context))
        setContentView(binding.root)
        findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.updatePadding(bottom = bottom + 12)
            insets
        }

        spec = GlassSettings.spec(context)
        binding.glassPreviewArt.setImageDrawable(
            BitmapDrawable(context.resources, CoverArt.of("aurora:glass-preview", 900)),
        )
        binding.glassPreviewCover.setImageDrawable(
            BitmapDrawable(context.resources, CoverArt.of("aurora:glass-cover", 160)),
        )
        // 预览是同一块 [GlassSurfaceView] 压在彩色插画上：面板本身不再采样，
        // 但压在彩色底上才看得出染色浓淡，压在纯白上和页面分不开
        binding.glassPreviewPanel.cornerRadiusDp = 18f
        binding.glassPreviewPanel.glassDark = false

        buildSlider()
        render()
    }

    private fun buildSlider() {
        // 复用播放器的进度线当滑块：同一套渐变和圆点，比 Material Slider 更贴这个 App 的形体
        binding.glassFrost.showKnob = true
        binding.glassFrost.knobRing = true
        binding.glassFrost.progress = spec.frost
        binding.glassFrost.onSeek = { updateFrost(it, persist = false) }
        binding.glassFrost.onSeekFinished = { updateFrost(it, persist = true) }
    }

    private fun render() {
        binding.glassFrostValue.text = context.getString(
            R.string.glass_frost_value,
            (spec.frost * 100f).roundToInt(),
        )
        binding.glassFrost.progress = spec.frost
        binding.glassPreviewPanel.glassOverride = spec
    }

    private fun updateFrost(frost: Float, persist: Boolean) {
        // clamped 而不是裸赋值：滑到最左端会停在磨砂下限，滑块跟着回弹，
        // 不会出现「滑块在 0、面板却按 0.3 画」这种自相矛盾
        spec = GlassSpec(frost).clamped()
        if (persist) GlassSettings.selectFrost(context, spec.frost)
        binding.glassFrost.progress = spec.frost
        binding.glassFrostValue.text = context.getString(
            R.string.glass_frost_value,
            (spec.frost * 100f).roundToInt(),
        )
        binding.glassPreviewPanel.glassOverride = spec
    }

    companion object {
        fun show(context: Context) {
            GlassDialog(context).show()
        }
    }
}