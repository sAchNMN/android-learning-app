package com.szc.learning.music.ui.home

import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.FragmentHomeBinding
import com.szc.learning.music.player.PlayerUiState
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.track.ShelfAdapter
import com.szc.learning.music.ui.track.TrackAdapter
import kotlinx.coroutines.launch

/**
 * 首页：一张主卡片 + 一条横滑 + 一张推荐列表。
 *
 * 刻意只保留这三块 —— 音乐类首页最容易犯的错是把"歌单/榜单/电台/推荐"全塞进来，
 * 结果每块都只看得到两首。留白比信息密度值钱。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy { ViewModelProvider(requireActivity())[MusicViewModel::class.java] }

    private val recentAdapter = ShelfAdapter { index ->
        viewModel.play(viewModel.recent.value, index)
    }
    private val recommendAdapter = TrackAdapter { index ->
        viewModel.play(recommend, index)
    }

    private var recommend: List<MusicTrack> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        binding.heroCard.clipToOutline = true

        binding.recentRail.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = recentAdapter
            setHasFixedSize(true)
        }
        binding.recommendList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = recommendAdapter
            setHasFixedSize(true)
        }

        binding.recentAll.setOnClickListener { viewModel.loadCloud(force = true) }
        binding.heroCard.setOnClickListener { playHero() }
        binding.heroPlay.setOnClickListener { playHero() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recent.collect { list ->
                        // 历史为空时退化成混合推荐，首页永远不会出现一条空横滑
                        val rail = list.ifEmpty { viewModel.mix(8) }
                        recentAdapter.submitList(rail)
                        binding.recentRail.visibility =
                            if (rail.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.cloud.collect { rebuildRecommend() }
                }
                launch {
                    viewModel.local.collect { rebuildRecommend() }
                }
                launch {
                    viewModel.playerState.collect { bindHero(it) }
                }
            }
        }
    }

    private fun rebuildRecommend() {
        recommend = viewModel.mix(RECOMMEND_SIZE)
        recommendAdapter.submitList(recommend)
        bindHero(viewModel.playerState.value)
    }

    private fun bindHero(state: PlayerUiState) {
        val track = state.track ?: recommend.firstOrNull() ?: run {
            binding.heroTitle.text = ""
            binding.heroArtist.text = ""
            return
        }
        binding.heroTitle.text = track.title
        binding.heroArtist.text = track.artist.ifBlank {
            getString(R.string.player_unknown_artist)
        }
        setHeroArt(track)
    }

    /** 主卡片用的是普通 ImageView（靠父容器裁圆角），所以这里不走 bindArt 的圆角逻辑。 */
    private fun setHeroArt(track: MusicTrack) {
        val fallback = BitmapDrawable(resources, CoverArt.of(track.key, 600))
        val art = track.artUri
        if (art.isNullOrBlank()) {
            binding.heroCover.setImageDrawable(fallback)
        } else {
            binding.heroCover.load(art) {
                placeholder(fallback)
                error(fallback)
                fallback(fallback)
                crossfade(220)
            }
        }
    }

    private fun playHero() {
        val state = viewModel.playerState.value
        val hero = state.track ?: recommend.firstOrNull() ?: return
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
    }
}
