package com.szc.learning.music.ui.widget

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import androidx.appcompat.widget.AppCompatImageView
import com.szc.learning.R

/**
 * 圆角封面。
 *
 * 替代 `ShapeableImageView`：后者用 [android.graphics.Path] 做裁剪，
 * 在硬件加速下会退化成一张软件光栅化的 path mask 纹理，**每帧对每张封面采样一次**。
 * 一屏十几行封面就是十几张 mask，GPU 上多出 1~2ms —— 90Hz 面板（11.1ms 预算）
 * 上这点钱正好压在掉帧线上。
 *
 * 这里改用 `outline` + `clipToOutline`：圆角矩形裁剪是硬件直出的圆角 clip，
 * 不需要额外纹理，也不需要逐帧采样。`setRoundRect` 而不是 `setPath`，
 * 否则又回到 path mask 那条路。视觉上和 squircle 的差别在 48dp 尺寸下看不出来。
 */
class CoverImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val radius: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.CoverImageView)
        radius = a.getDimension(R.styleable.CoverImageView_coverRadius, 0f)
        a.recycle()

        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }
}
