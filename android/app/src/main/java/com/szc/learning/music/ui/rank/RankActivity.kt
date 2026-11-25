package com.szc.learning.music.ui.rank

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.ActivityRankBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeManager
import com.szc.learning.ui.theme.clearWindowBackground
import com.szc.learning.ui.theme.themeColor
import kotlinx.coroutines.launch

/**
 * 排行榜：热歌榜 / 飙升榜。
 *
 * 这两个榜是同一份数据的两种读法，所以共用一个页面 —— 差异只有标题、
 * 右侧那个数字的含义、以及统计口径那句说明。
 *
 * 页面**没有离线数据**。榜单是跨用户聚合，断网时显示"查不了"是诚实的；
 * 显示一份上次缓存的结果，用户会以为自己看到的是最新的排名。
 */
class RankActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRankBinding
    private val viewModel: MusicViewModel by viewModels()

    private var type: String = RankType.HOT
    private var adapterType: String = RankType.HOT
    private var adapter = RankAdapter(rising = false) { index -> play(index) }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyPalette(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRankBinding.inflate(layoutInflater)
        setContentView(binding.root)
        clearWindowBackground()

        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rankRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.rankBack.setOnClickListener { finish() }
        binding.rankRefresh.setOnClickListener {
            viewModel.loadRank(type, refresh = true)
        }

        binding.rankList.layoutManager = LinearLayoutManager(this)
        binding.rankList.adapter = adapter

        buildSegment()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.rank.collect { render(it) }
            }
        }
    }

    /**
     * 从榜单回到前台时重查一次。
     *
     * 用户很可能是"在榜单里点了一首、听完又退回来"，这时候排名可能已经变了。
     * 走 [MusicViewModel.loadRank] 的 30 秒节流，短时间内反复进出不会打爆后端。
     */
    override fun onStart() {
        super.onStart()
        if (::binding.isInitialized) viewModel.loadRank(type, refresh = true)
    }

    // ---------------------------------------------------------------- 渲染

    private fun render(state: RankUiState) {
        // 切换榜单要换一个 adapter：右侧数字的含义变了，行内逻辑跟着变
        if (state.type != adapterType) {
            adapterType = state.type
            adapter = RankAdapter(rising = state.type == RankType.RISING) { index -> play(index) }
            binding.rankList.adapter = adapter
        }
        adapter.submitList(state.tracks)

        binding.rankLoading.isVisible = state.loading && state.tracks.isEmpty()
        binding.rankSubtitle.text = subtitleOf(state)

        val blank = state.tracks.isEmpty() && !state.loading
        binding.rankEmpty.isVisible = blank
        if (blank) {
            val unavailable = state.message != null
            binding.rankEmptyTitle.setText(
                if (unavailable) R.string.rank_unavailable else R.string.rank_empty_title,
            )
            binding.rankEmptyHint.text = state.message ?: getString(R.string.rank_empty_hint)
        }
    }

    /**
     * 榜单脚注。
     *
     * 空榜时也要有一句话：用户看到一片空白，最想知道的是"没人听"还是"功能坏了"。
     * 后端给了原因（`message`）就直接显示原因。
     */
    private fun subtitleOf(state: RankUiState): String {
        if (state.message != null) return state.message
        if (state.type == RankType.RISING) {
            return getString(R.string.rank_subtitle_rising)
        }
        if (state.eventCount <= 0L) {
            return getString(R.string.rank_subtitle_empty)
        }
        return getString(R.string.rank_subtitle, state.eventCount, clockOf(state.updatedAt))
    }

    /** `2026-11-24 23:40` → `23:40`。后端给的是完整的日期时间，这里只要时分。 */
    private fun clockOf(updatedAt: String?): String =
        updatedAt?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "--:--"

    private fun buildSegment() {
        val row = binding.rankSegment
        row.removeAllViews()
        val options = listOf(
            RankType.HOT to R.string.rank_tab_hot,
            RankType.RISING to R.string.rank_tab_rising,
        )
        options.forEach { (value, labelRes) ->
            val label = TextView(this).apply {
                text = getString(labelRes)
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (type == value) return@setOnClickListener
                    type = value
                    applySegmentLook()
                    viewModel.loadRank(value, refresh = true)
                }
            }
            row.addView(
                label,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        applySegmentLook()
    }

    private fun applySegmentLook() {
        val options = listOf(RankType.HOT, RankType.RISING)
        for (index in 0 until binding.rankSegment.childCount) {
            val label = binding.rankSegment.getChildAt(index) as? TextView ?: continue
            val selected = options.getOrNull(index) == type
            label.background = if (selected) getDrawable(R.drawable.bg_segment_thumb) else null
            label.setTextColor(themeColor(if (selected) R.attr.auInk else R.attr.auInkSecondary))
            label.typeface = Typeface.create(
                "sans-serif-medium",
                if (selected) Typeface.BOLD else Typeface.NORMAL,
            )
        }
    }

    // ---------------------------------------------------------------- 播放

    /**
     * 整张榜进队列，从点的这首开始。
     *
     * 榜单曲目只有平台 id，没有地址；[MusicViewModel.playTracks] 会先把在线曲目
     * 换好地址再起播，这里不能直接 `player.play`。
     */
    private fun play(index: Int) {
        val ranked = viewModel.rank.value.tracks
        if (index !in ranked.indices) return
        viewModel.playTracks(ranked.map { it.track }, index) { event ->
            when (event) {
                is OnlinePlayEvent.Ready -> startActivity(MainActivity.playerIntent(this))
                is OnlinePlayEvent.Failed -> toast(event.message)
            }
        }
    }

    companion object {
        fun start(context: android.content.Context) {
            context.startActivity(
                android.content.Intent(context, RankActivity::class.java),
            )
        }
    }
}
