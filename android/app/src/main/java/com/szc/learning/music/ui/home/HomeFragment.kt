package com.szc.learning.music.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.FragmentHomeBinding
import com.szc.learning.music.ui.MusicViewModel
import kotlinx.coroutines.launch

/**
 * 首页：一张主卡片 + 一条横滑 + 一张推荐列表。
 *
 * 刻意只留这三块 —— 音乐首页最容易犯的错是把歌单/榜单/电台全塞进来，
 * 结果每块都只看得到两首。留白比信息密度值钱。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private lateinit var adapter: HomeAdapter

    private var recentTracks: List<MusicTrack> = emptyList()
    private var railTracks: List<MusicTrack> = emptyList()
    private var recommend: List<MusicTrack> = emptyList()
    private var heroTrack: MusicTrack? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        adapter = HomeAdapter(
            onHeroClick = ::playHero,
            onHeroPlay = ::playHero,
            onSectionAction = { tag -> if (tag == TAG_RECENT) viewModel.loadCloud(force = true) },
            onRailClick = { track ->
                val index = railTracks.indexOfFirst { it.key == track.key }
                if (index >= 0) viewModel.play(railTracks, index)
            },
            onRowClick = { track ->
                val index = recommend.indexOfFirst { it.key == track.key }
                if (index >= 0) viewModel.play(recommend, index)
            },
        )

        binding.homeList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HomeFragment.adapter
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recent.collect { list ->
                        recentTracks = list
                        rebuild()
                    }
                }
                launch { viewModel.cloud.collect { rebuild() } }
                launch { viewModel.local.collect { rebuild() } }
                launch {
                    viewModel.playerState.collect { state ->
                        val next = state.track
                        if (next?.key != heroTrack?.key) {
                            heroTrack = next
                            rebuild()
                        }
                    }
                }
            }
        }
    }

    /**
     * 重建条目列表。
     *
     * 横滑在没有历史时退化成混合推荐，首页永远不会出现一条空轨道；
     * 主卡片优先跟随"正在播放"，没有播放时用推荐第一首。
     */
    private fun rebuild() {
        recommend = viewModel.mix(RECOMMEND_SIZE)
        railTracks = recentTracks.ifEmpty { viewModel.mix(RAIL_SIZE) }

        val hero = heroTrack
            ?: viewModel.playerState.value.track
            ?: recommend.firstOrNull()

        val items = buildList {
            add(HomeItem.Header(hero))
            if (railTracks.isNotEmpty()) {
                add(
                    HomeItem.Section(
                        title = getString(R.string.home_recent),
                        action = getString(R.string.home_see_all),
                        actionTag = TAG_RECENT,
                    ),
                )
                add(HomeItem.Rail(railTracks))
            }
            if (recommend.isNotEmpty()) {
                add(
                    HomeItem.Section(
                        title = getString(R.string.home_recommend),
                        action = null,
                        actionTag = TAG_RECOMMEND,
                    ),
                )
                recommend.forEach { add(HomeItem.Row(it)) }
            }
        }
        adapter.submitList(items)
    }

    private fun playHero() {
        val state = viewModel.playerState.value
        val hero = state.track ?: heroTrack ?: recommend.firstOrNull() ?: return
        if (state.track?.key == hero.key) {
            viewModel.toggle()
            return
        }
        val index = recommend.indexOfFirst { it.key == hero.key }
        if (index >= 0) {
            viewModel.play(recommend, index)
        } else {
            viewModel.play(listOf(hero), 0)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val RECOMMEND_SIZE = 6
        const val RAIL_SIZE = 8
        const val TAG_RECENT = "recent"
        const val TAG_RECOMMEND = "recommend"
    }
}
