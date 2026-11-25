package com.szc.learning.music.ui.report

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.szc.learning.R
import com.szc.learning.databinding.ActivityWeekReportBinding
import com.szc.learning.music.data.DayPoint
import com.szc.learning.music.data.WeekReport
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.ui.theme.themeColor
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 听歌周报。
 *
 * 数据全在本机（[com.szc.learning.music.data.ListenDiaryStore]），不走后端：
 * 周报这种东西一进服务端就要面对「谁的 deviceId 对应谁」的问题，
 * 而 App 本来就没有账号体系。本机算完直接画，没网也能看。
 *
 * 图表是代码铺的而不是七个固定 id 的 XML：天数一改（以后可能出月报），
 * 布局不用跟着动。
 */
class WeekReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWeekReportBinding
    private val viewModel: MusicViewModel by viewModels()
    private val dayLabel = SimpleDateFormat("M/d", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWeekReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.reportRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.reportBack.setOnClickListener { finish() }
        render(viewModel.weekReport())
    }

    private fun render(report: WeekReport) {
        binding.reportLifetime.text = getString(
            R.string.report_lifetime,
            formatDuration(viewModel.listenMinutes.value * 60),
        )

        if (report.isEmpty) {
            binding.reportTotal.text = getString(R.string.report_total_zero)
            binding.reportSub.text = getString(R.string.report_no_play_days)
            binding.reportChart.visibility = View.GONE
            binding.reportTopHeader.visibility = View.GONE
            binding.reportTop.visibility = View.GONE
            binding.reportEmpty.visibility = View.VISIBLE
            return
        }

        binding.reportTotal.text = formatDuration(report.totalSeconds)
        val best = report.bestDay
        binding.reportSub.text = if (best != null) {
            getString(R.string.report_sub, report.activeDays, formatDuration(best.seconds))
        } else {
            getString(R.string.report_no_play_days)
        }

        renderChart(report.days)
        renderTop(report)
    }

    private fun renderChart(days: List<DayPoint>) {
        binding.reportChart.removeAllViews()
        val accent = themeColor(R.attr.auAccent)
        val max = days.maxOfOrNull { it.seconds }?.coerceAtLeast(1L) ?: 1L

        days.forEach { day ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // 柱子在容器里贴底，容器高度固定 —— 高度差才是"哪天真在听"的信息
            val barArea = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(CHART_HEIGHT_DP).toInt(),
                )
            }
            val ratio = day.seconds.toFloat() / max
            // 有播放就至少给 4dp：0 值的天是"没听"，不该和"听了一分钟"看起来一样
            val barHeight = if (day.seconds <= 0) dp(BAR_MIN_DP).toInt() else {
                (dp(CHART_HEIGHT_DP) * ratio).toInt().coerceAtLeast(dp(BAR_MIN_DP).toInt())
            }
            val bar = View(this).apply {
                setBackgroundColor(if (day.seconds > 0) accent else themeColor(R.attr.auInkSecondary))
                alpha = if (day.seconds > 0) 1f else 0.18f
                layoutParams = LinearLayout.LayoutParams(dp(BAR_WIDTH_DP).toInt(), barHeight)
            }
            barArea.addView(bar)
            column.addView(barArea)

            column.addView(
                TextView(this).apply {
                    text = dayLabel.format(parseDate(day.date))
                    textSize = 10f
                    setTextColor(themeColor(R.attr.auInkSecondary))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(6f).toInt() }
                },
            )
            binding.reportChart.addView(column)
        }
    }

    private fun renderTop(report: WeekReport) {
        binding.reportTop.removeAllViews()
        if (report.topTracks.isEmpty()) {
            binding.reportTopHeader.visibility = View.GONE
            return
        }
        binding.reportTopHeader.visibility = View.VISIBLE
        report.topTracks.forEachIndexed { index, stat ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(ROW_HEIGHT_DP).toInt(),
                )
            }
            row.addView(
                TextView(this).apply {
                    text = (index + 1).toString()
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(themeColor(if (index == 0) R.attr.auAccent else R.attr.auInkSecondary))
                    width = dp(RANK_WIDTH_DP).toInt()
                },
            )
            row.addView(
                TextView(this).apply {
                    text = stat.title.ifBlank { getString(R.string.report_unknown_track) }
                    textSize = 14f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(themeColor(R.attr.auInk))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                TextView(this).apply {
                    text = formatDuration(stat.seconds)
                    textSize = 12f
                    setTextColor(themeColor(R.attr.auInkSecondary))
                },
            )
            binding.reportTop.addView(row)
        }
    }

    /** 日记里存的是 `yyyy-MM-dd`，图表上只用 `M/d`。 */
    private fun parseDate(raw: String): java.util.Date =
        runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw) }
            .getOrNull() ?: java.util.Date()

    /** 秒 → `3 小时 12 分` / `42 分钟`。周报里不需要秒，粒度到分钟就够。 */
    private fun formatDuration(seconds: Long): String {
        val totalMinutes = seconds / 60
        return when {
            totalMinutes >= 60 -> {
                val hours = totalMinutes / 60
                val minutes = totalMinutes % 60
                if (minutes == 0L) {
                    getString(R.string.report_hours, hours)
                } else {
                    getString(R.string.report_hours_minutes, hours, minutes)
                }
            }
            totalMinutes >= 1 -> getString(R.string.report_minutes, totalMinutes)
            seconds > 0 -> getString(R.string.report_less_minute)
            else -> getString(R.string.report_zero)
        }
    }

    companion object {
        private const val CHART_HEIGHT_DP = 120f
        private const val BAR_WIDTH_DP = 16f
        private const val BAR_MIN_DP = 4f
        private const val ROW_HEIGHT_DP = 38f
        private const val RANK_WIDTH_DP = 24f

        fun start(context: Context) {
            context.startActivity(Intent(context, WeekReportActivity::class.java))
        }
    }
}
