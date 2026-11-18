package com.szc.learning.music.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.databinding.FragmentRecommendBinding
import com.szc.learning.databinding.ItemChartCardBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.Playlists
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 推荐页：
 * 每日推荐（按天换一批）+ 歌单 + 歌手 + 三个榜。
 *
 * 数据全部来自本机曲库，没有后端推荐算法 —— 所谓"猜你喜欢"就是
 * 把云端和本地混在一起、按日期做一次稳定洗牌。至少它每次都不一样，
 * 又不会在同一天里来回跳。
 */
class RecommendFragment : Fragment(R.layout.fragment_recommend) {

    private var _binding: FragmentRecommendBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private val playlistAdapter = PlaylistRailAdapter { playlist ->
        viewModel.play(playlist.tracks, 0)
    }

    private val artistAdapter = ArtistRailAdapter { artist ->
        val tracks = viewModel.allTracks().filter { it.artist == artist.name }
        viewModel.play(tracks, 0)
    }

    private var dailySeed = System.currentTimeMillis() / DAY_MS
    private var dailyTracks = emptyList<com.szc.learning.core.common.music.MusicTrack>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRecommendBinding.bind(view)

        binding.recommendSkeleton.style = ShimmerSkeletonView.Style.RECOMMEND

        binding.recommendPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
            setHasFixedSize(true)
        }
        binding.recommendArtists.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = artistAdapter
            setHasFixedSize(true)
        }

        binding.recommendRefresh.setOnClickListener {
            dailySeed = System.currentTimeMillis()
            rebuild()
        }
        binding.dailyCard.setOnClickListener { playDaily() }
        binding.dailyPlay.setOnClickListener { playDaily() }
        installInsets()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    viewModel.cloud,
                    viewModel.local,
                    viewModel.recent,
                    viewModel.favorites,
                ) { _, _, _, _ -> Unit }.collect { rebuild() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.cloudLoading.collect { rebuild() }
            }
        }
        rebuild()
    }

    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.recommendScroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = bars.top + view.context.dp(6f).toInt()
            val bottom = view.context.dp(160f).toInt() + bars.bottom
            view.updatePadding(top = top, bottom = bottom)
            binding.recommendSkeleton.updatePadding(top = top, bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.recommendScroll)
    }

    private fun rebuild() {
        val context = context ?: return
        val all = viewModel.allTracks()
        val local = viewModel.local.value

        val loading = all.isEmpty() && viewModel.cloudLoading.value
        binding.recommendSkeleton.visibility = if (loading) View.VISIBLE else View.GONE
        binding.recommendScroll.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        if (loading) return

        val playlists = Playlists.build(
            context,
            viewModel.cloud.value,
            local,
            viewModel.recent.value,
            viewModel.favoriteTracks(),
            limit = 8,
        )
        playlistAdapter.submitList(playlists)
        binding.recommendMorePlaylists.text =
            getString(R.string.recommend_playlist_count, playlists.size)

        val artists = all
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist }
            .map { (name, tracks) -> Artist(name, tracks.size) }
            .sortedByDescending { it.trackCount }
            .take(10)
        artistAdapter.submitList(artists)

        dailyTracks = all.shuffled(Random(dailySeed * 31)).take(DAILY_COUNT)
        binding.dailyTitle.text = getString(R.string.recommend_daily_title, dailyTracks.size)
        binding.dailySubtitle.setText(R.string.recommend_daily_subtitle)
        binding.dailyCard.isEnabled = dailyTracks.isNotEmpty()

        binding.recommendCharts.removeAllViews()
        addChart(1, R.string.chart_hot, all.take(CHART_COUNT))
        addChart(2, R.string.chart_new, all.sortedByDescending { it.key }.take(CHART_COUNT))
        addChart(3, R.string.chart_local, local.take(CHART_COUNT))
    }

    private fun addChart(index: Int, @StringRes title: Int, tracks: List<com.szc.learning.core.common.music.MusicTrack>) {
        if (tracks.isEmpty()) return
        val card = ItemChartCardBinding.inflate(layoutInflater, binding.recommendCharts, false)
        card.chartIndex.text = index.toString()
        card.chartTitle.setText(title)
        card.chartSubtitle.text = tracks.take(3).joinToString(" / ") { it.title }
        card.root.setOnClickListener { viewModel.play(tracks, 0) }
        card.chartPlay.setOnClickListener { viewModel.play(tracks, 0) }
        binding.recommendCharts.addView(card.root)
    }

    private fun playDaily() {
        if (dailyTracks.isNotEmpty()) viewModel.play(dailyTracks, 0)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val DAILY_COUNT = 12
        const val CHART_COUNT = 8
    }
}