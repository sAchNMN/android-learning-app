package com.szc.learning.music.ui.mine

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentMineBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.showAbout
import com.szc.learning.music.ui.common.showListenStats
import com.szc.learning.music.ui.glass.GlassDialog
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.source.SourceActivity
import com.szc.learning.music.ui.track.TrackCardAdapter
import com.szc.learning.music.ui.track.TrackListActivity
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import com.szc.learning.ui.theme.ThemeDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 我的页：资料 / 三项数据 / 入口宫格 / 我喜欢 / 最近播放。
 *
 * 这里没有会员、积分、签到、免流、广告位 —— 参考产品上那一片运营入口
 * 对自用播放器没有意义，全部换成本地功能。数据也都是真的：
 * 听歌时长按秒累计，收藏落 SharedPreferences，最近播放走播放器回调。
 */
class MineFragment : Fragment(R.layout.fragment_mine) {

    private var _binding: FragmentMineBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private val entryAdapter = MineEntryAdapter(MineEntryAdapter.defaults()) { entry ->
        handleEntry(entry)
    }

    private val recentAdapter = TrackCardAdapter { track ->
        val recent = viewModel.recent.value
        val index = recent.indexOfFirst { it.key == track.key }
        viewModel.play(recent, index.coerceAtLeast(0))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentMineBinding.bind(view)

        binding.mineEntries.apply {
            layoutManager = GridLayoutManager(requireContext(), 4)
            adapter = entryAdapter
            setHasFixedSize(true)
        }
        binding.mineRecent.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
            setHasFixedSize(true)
        }

        binding.mineAvatar.setImageDrawable(
            BitmapDrawable(resources, CoverArt.of("aurora:profile", 200)),
        )
        binding.mineFavoritesCover.setImageDrawable(
            BitmapDrawable(resources, CoverArt.of("aurora:favorites", 200)),
        )

        binding.mineAbout.setOnClickListener { showAbout(requireContext()) }
        binding.mineTheme.setOnClickListener {
            (activity as? MainActivity)?.let { ThemeDialog.show(it) }
        }
        binding.mineGlass.setOnClickListener {
            GlassDialog.show(requireContext())
        }
        binding.mineStats.setOnClickListener {
            showListenStats(
                requireContext(),
                viewModel.listenMinutes.value,
                viewModel.favorites.value.size,
                viewModel.recent.value.size,
            )
        }
        binding.mineFavoritesCard.setOnClickListener { openFavorites() }
        binding.mineFavoritesPlay.setOnClickListener { playFavorites() }
        binding.mineRecentAll.setOnClickListener {
            TrackListActivity.startRecent(requireContext())
        }
        binding.mineFavoritesPlay.installSpringPress(0.88f)
        binding.mineFavoritesCard.installSpringPress(0.985f)
        installInsets()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.cloud,
                    viewModel.local,
                    viewModel.recent,
                    viewModel.favorites,
                    viewModel.listenMinutes,
                ) { _, _, _, _, _ -> Unit }.collect { render() }
            }
        }
        render()
    }

    /** 内容铺到系统栏下面；静态标题和底部内容按 inset 让位。 */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = bars.top + view.context.dp(6f).toInt()
            val bottom = view.context.dp(160f).toInt() + bars.bottom
            view.updatePadding(top = top, bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun render() {
        val favorites = viewModel.favoriteTracks()
        val recent = viewModel.recent.value
        val all = viewModel.allTracks()

        binding.statMinutes.text =
            getString(R.string.mine_minutes, viewModel.listenMinutes.value)
        binding.statFavorites.text = favorites.size.toString()
        binding.statRecent.text = recent.size.toString()

        binding.mineName.setText(R.string.mine_user)
        binding.mineSubtitle.text = getString(R.string.mine_tracks, all.size)
        binding.mineFavoritesCount.text = getString(R.string.mine_count, favorites.size)

        recentAdapter.submitList(recent.take(10))
        binding.mineRecent.visibility =
            if (recent.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun handleEntry(entry: MineEntry) {
        when (entry.key) {
            MineEntryAdapter.KEY_FAVORITES -> openFavorites()
            MineEntryAdapter.KEY_RECENT -> TrackListActivity.startRecent(requireContext())
            MineEntryAdapter.KEY_LOCAL -> mainActivity()?.openLocalTab()
            MineEntryAdapter.KEY_CLOUD -> TrackListActivity.startCloud(requireContext())
            MineEntryAdapter.KEY_PLAYER -> mainActivity()?.openTab(MainActivity.INDEX_PLAYER)
            MineEntryAdapter.KEY_STATS -> showListenStats(
                requireContext(),
                viewModel.listenMinutes.value,
                viewModel.favorites.value.size,
                viewModel.recent.value.size,
            )

            MineEntryAdapter.KEY_SOURCES -> SourceActivity.start(requireContext())

            MineEntryAdapter.KEY_RESCAN -> {
                viewModel.refreshLocal()
                requireContext().toast(R.string.local_rescanned)
            }

            else -> showAbout(requireContext())
        }
    }

    private fun openFavorites() {
        TrackListActivity.startFavorites(requireContext())
    }

    private fun playFavorites() {
        val favorites = viewModel.favoriteTracks()
        if (favorites.isEmpty()) {
            requireContext().toast(R.string.mine_empty_favorites)
            return
        }
        viewModel.play(favorites, 0)
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}