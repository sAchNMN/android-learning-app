package com.szc.learning.music.ui.now

import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.FragmentActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.DialogPlayOptionsBinding
import com.szc.learning.databinding.ItemPlayOptionBinding
import com.szc.learning.music.data.source.AudioQuality
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.playlist.SaveToPlaylistSheet
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
    /** 歌词偏移改完之后回调：调用方要重新解析一遍，已渲染的时间轴不会自己动。 */
    private val onLyricOffsetChanged: () -> Unit = {},
) {

    fun show() {
        val dialog = BottomSheetDialog(activity)
        val binding = DialogPlayOptionsBinding.inflate(activity.layoutInflater)
        dialog.setContentView(binding.root)

        bindSleepOptions(binding.optionsSleep, dialog)
        bindSpeedOptions(binding.optionsSpeed, dialog)
        bindQualityOptions(binding, dialog)
        bindActions(binding.optionsActions, dialog)

        dialog.show()
    }

    /**
     * 动作行：纯点击项，不带勾选。
     *
     * 和设置项分开，是因为它们的语义不同 —— 上面三组是「记住这个选择」，
     * 这里点一下就走（保存到歌单）。混在一起会让人以为它也保存状态。
     */
    private fun bindActions(container: LinearLayout, dialog: BottomSheetDialog) {
        val save = ItemPlayOptionBinding.inflate(activity.layoutInflater, container, false)
        save.optionTitle.text = activity.getString(R.string.local_playlist_save_to)
        save.optionCheck.visibility = View.INVISIBLE
        save.optionNote.visibility = View.GONE
        save.root.setOnClickListener {
            dialog.dismiss()
            SaveToPlaylistSheet.show(activity, viewModel)
        }
        container.addView(save.root)

        val offset = ItemPlayOptionBinding.inflate(activity.layoutInflater, container, false)
        offset.optionTitle.text = activity.getString(R.string.lyric_offset_action)
        offset.optionCheck.visibility = View.INVISIBLE
        offset.optionNote.visibility = View.VISIBLE
        offset.optionNote.text = formatOffset(viewModel.currentTrack()?.let {
            viewModel.lyricOffset(it.key)
        } ?: 0L)
        offset.root.setOnClickListener {
            dialog.dismiss()
            showLyricOffsetDialog()
        }
        container.addView(offset.root)

        val loop = ItemPlayOptionBinding.inflate(activity.layoutInflater, container, false)
        loop.optionTitle.text = activity.getString(R.string.loop_action)
        loop.optionCheck.visibility = View.INVISIBLE
        val range = viewModel.loopRange.value
        val draft = viewModel.loopDraft.value
        loop.optionNote.visibility = if (range == null && draft == null) View.GONE else View.VISIBLE
        loop.optionNote.text = when {
            range != null -> "${formatDuration(range.startMs)}-${formatDuration(range.endMs)}"
            draft != null -> "A ${formatDuration(draft)}"
            else -> ""
        }
        loop.root.setOnClickListener {
            dialog.dismiss()
            showLoopDialog()
        }
        container.addView(loop.root)
    }

    /**
     * AB 段循环设置。
     *
     * 和歌词校准一样调完重新弹出：圈一段副歌往往要来回挪两三次，
     * 每次都退出重进「更多」太笨。点「关闭循环」或返回键退出。
     */
    private fun showLoopDialog() {
        val range = viewModel.loopRange.value
        val draft = viewModel.loopDraft.value
        val title = when {
            range != null -> activity.getString(
                R.string.loop_title_active,
                formatDuration(range.startMs),
                formatDuration(range.endMs),
            )
            draft != null -> activity.getString(R.string.loop_title_draft, formatDuration(draft))
            else -> activity.getString(R.string.loop_title_none)
        }
        val options = arrayOf(
            activity.getString(R.string.loop_set_a),
            activity.getString(R.string.loop_set_b),
            activity.getString(R.string.loop_clear),
        )
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.markLoopStart()
                    1 -> if (!viewModel.markLoopEnd()) {
                        activity.toast(R.string.loop_invalid)
                    }
                    else -> viewModel.clearLoop()
                }
                showLoopDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 歌词校准。
     *
     * 一次只调 ±0.5 秒，调完重新弹出同一个框 —— 对不齐的时候往往要连点几下，
     * 每点一次都关窗再点开「更多」是纯粹的手腕损耗。
     */
    private fun showLyricOffsetDialog() {
        val track = viewModel.currentTrack() ?: run {
            activity.toast(R.string.no_track_playing)
            return
        }
        val current = viewModel.lyricOffset(track.key)
        val options = arrayOf(
            activity.getString(R.string.lyric_offset_minus),
            activity.getString(R.string.lyric_offset_plus),
            activity.getString(R.string.lyric_offset_reset),
        )
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.lyric_offset_title, formatOffset(current)))
            .setItems(options) { _, which ->
                val next = when (which) {
                    0 -> viewModel.shiftLyricOffset(track.key, -OFFSET_STEP_MS)
                    1 -> viewModel.shiftLyricOffset(track.key, OFFSET_STEP_MS)
                    else -> viewModel.resetLyricOffset(track.key)
                }
                onLyricOffsetChanged()
                activity.toast(activity.getString(R.string.lyric_offset_applied, formatOffset(next)))
                showLyricOffsetDialog()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** `+0.5s` / `-0.5s` / `0`。整数秒不带小数点。 */
    private fun formatOffset(offsetMs: Long): String {
        if (offsetMs == 0L) return "0"
        val seconds = offsetMs / 1000.0
        val sign = if (seconds > 0) "+" else "-"
        val abs = kotlin.math.abs(seconds)
        val text = if (abs == abs.toInt().toDouble()) abs.toInt().toString() else "%.1f".format(abs)
        return "$sign${text}s"
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
     * 音质档位。
     *
     * 会员档位**不置灰**，只在行尾标「需会员」：能不能真拿到由平台按账号权限决定，
     * 本地禁用会让「我是黑胶会员但这里点不了」变成新的困惑。真正的权限校验
     * 在取址响应里（level / freeTrialInfo），拿到降级结果时再提示。
     */
    private fun bindQualityOptions(binding: DialogPlayOptionsBinding, dialog: BottomSheetDialog) {
        val loggedIn = viewModel.hasOnlineCookie()
        binding.qualityCaption.text = activity.getString(
            if (loggedIn) R.string.player_quality else R.string.quality_caption_guest,
        )

        val current = viewModel.audioQuality()
        val checkTint = ColorStateList.valueOf(activity.themeColor(R.attr.auAccent))
        AudioQuality.entries.forEach { quality ->
            val row = ItemPlayOptionBinding.inflate(activity.layoutInflater, binding.optionsQuality, false)
            row.optionTitle.text = activity.getString(quality.labelRes)
            row.optionNote.visibility = if (quality.vipOnly) View.VISIBLE else View.GONE
            if (quality.vipOnly) row.optionNote.text = activity.getString(R.string.quality_vip_only)
            row.optionCheck.visibility = if (quality == current) View.VISIBLE else View.INVISIBLE
            row.optionCheck.imageTintList = checkTint
            row.root.setOnClickListener {
                viewModel.setAudioQuality(quality)
                activity.toast(activity.getString(R.string.quality_applied, activity.getString(quality.labelRes)))
                dialog.dismiss()
            }
            binding.optionsQuality.addView(row.root)
        }
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

        /** 校准步长。0.5 秒够细，又不会让人点几十下才挪一秒。 */
        const val OFFSET_STEP_MS = 500L
    }
}
