package com.szc.learning.music.ui.now

import android.content.res.ColorStateList
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.databinding.DialogPlayOptionsBinding
import com.szc.learning.databinding.ItemPlayOptionBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.ui.theme.themeColor
import kotlin.math.abs

/**
 * 播放设置弹层：定时关闭 + 倍速。
 *
 * 为什么单独放成弹层，而不是往底部控制条再塞两个按钮：播放页底部已经有
 * 收藏 / 上一首 / 播放 / 下一首 / 播放方式五个控件，再塞就挤成一排图标了。
 * 这两项都是低频设置，收进「更多」里是对的层次。
 *
 * 样式跟随队列弹层（[com.szc.learning.music.ui.common.showQueueSheet]）：
 * 同一个 `bg_dialog`、同一根拖拽把手、同一套行高。
 */
class NowPlayingMenuSheet(
    private val activity: FragmentActivity,
    private val viewModel: MusicViewModel,
    private val onSleepChanged: (Int) -> Unit,
    private val onSpeedChanged: (Float) -> Unit,
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = DialogPlayOptionsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)

        bindSleepOptions(binding.optionsSleep, dialog)
        bindSpeedOptions(binding.optionsSpeed, dialog)

        dialog.show()
    }

    private fun bindSleepOptions(container: LinearLayout, dialog: BottomSheetDialog) {
        val endAt = viewModel.sleepTimerEndAt.value
        val atEndOfTrack = viewModel.sleepAtEndOfTrack.value
        val labels = listOf(
            activity.getString(R.string.sleep_off),
            activity.getString(R.string.sleep_minutes, 15),
            activity.getString(R.string.sleep_minutes, 30),
            activity.getString(R.string.sleep_minutes, 60),
            activity.getString(R.string.sleep_track_end),
        )
        val values = listOf(0, 15, 30, 60, SLEEP_TRACK_END)
        rows(
            container = container,
            labels = labels,
            isSelected = { index ->
                when (values[index]) {
                    SLEEP_TRACK_END -> atEndOfTrack && endAt == null
                    0 -> endAt == null && !atEndOfTrack
                    // 倒计时中途回到这个面板：不猜"当初选的哪个"，宁可一个都不勾
                    else -> false
                }
            },
            onPick = { index ->
                val minutes = values[index]
                viewModel.setSleepTimer(minutes)
                onSleepChanged(minutes)
                dialog.dismiss()
            },
        )
    }

    private fun bindSpeedOptions(container: LinearLayout, dialog: BottomSheetDialog) {
        val current = viewModel.speed
        val values = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        val labels = values.map { formatSpeed(it) }
        rows(
            container = container,
            labels = labels,
            isSelected = { index -> abs(values[index] - current) < 0.01f },
            onPick = { index ->
                viewModel.setSpeed(values[index])
                onSpeedChanged(values[index])
                dialog.dismiss()
            },
        )
    }

    /**
     * 铺一批单选行。
     *
     * 行布局用 include 不划算（id 会重复），这里直接 inflate 同一个 item 布局：
     * 选项数量随业务变（以后可能加 90 分钟），写字死 id 的 XML 每加一项都要改布局。
     */
    private fun rows(
        container: LinearLayout,
        labels: List<String>,
        isSelected: (Int) -> Boolean,
        onPick: (Int) -> Unit,
    ) {
        val checkTint = ColorStateList.valueOf(activity.themeColor(R.attr.auAccent))
        labels.forEachIndexed { index, label ->
            val row = ItemPlayOptionBinding.inflate(activity.layoutInflater, container, false)
            row.optionTitle.text = label
            row.optionCheck.visibility =
                if (isSelected(index)) android.view.View.VISIBLE else android.view.View.INVISIBLE
            row.optionCheck.imageTintList = checkTint
            row.root.setOnClickListener { onPick(index) }
            container.addView(row.root)
        }
    }

    /** 0.75 → "0.75"，1.0 → "1"，2.0 → "2"：整数倍速不带小数点更干净。 */
    private fun formatSpeed(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) {
            speed.toInt().toString()
        } else {
            speed.toString()
        }
        return activity.getString(R.string.speed_value, text)
    }

    private companion object {
        /** 与 PlayerController.setSleepTimer 的约定一致。 */
        const val SLEEP_TRACK_END = -1
    }
}
