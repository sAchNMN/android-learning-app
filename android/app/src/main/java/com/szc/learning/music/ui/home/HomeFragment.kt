package com.szc.learning.music.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentHomeBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.common.Playlists
import com.szc.learning.music.ui.common.showAbout
import com.szc.learning.music.ui.search.SearchActivity
import com.szc.learning.music.ui.track.TrackListActivity
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 首页。
 *
 * 结构对齐参考图：大标题 + 搜索 + 轮播 + 五个快捷入口 + 歌单补给站 + 优质好歌。
 * 没有广告位、没有会员卡 —— 这是自用播放器，不塞运营模块。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var entrancePlayed = false
    private var skeletonShown = false
    private var skeletonSince = 0L
    private var skeletonWaitScheduled = false

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private val adapter = HomeAdapter(
        onSearch = { openSearch() },
        onMenu = { showMenu() },
        onVideo = { mainActivity()?.openTab(MainActivity.INDEX_PLAYER) },
        onQuick = { action -> handleQuick(action) },
        onHeroPlay = { track -> playTrack(track) },
        onSectionAction = { tag -> handleSectionAction(tag) },
        onPlaylist = { playlist -> viewModel.play(playlist.tracks, 0) },
        onTrack = { track -> playTrack(track) },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentHomeBinding.bind(view)

        binding.homeList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HomeFragment.adapter
            setHasFixedSize(true)
            itemAnimator = null
        }
        binding.homeSkeleton.style = ShimmerSkeletonView.Style.HOME
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
                combine(viewModel.cloudLoading, viewModel.localLoading) { cloud, local ->
                    cloud || local
                }.collect { rebuild() }
            }
        }
        rebuild()
    }

    /**
     * 页面铺到整屏，标题用 status bar 的高度让位；列表底部再给 dock + 手势条留空间。
     * `clipToPadding=false` 时滚动中的内容可以钻到状态栏/手势条下面。
     */
    private fun installInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeList) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = bars.top + view.context.dp(6f).toInt()
            val bottom = view.context.dp(156f).toInt() + bars.bottom
            view.updatePadding(top = top, bottom = bottom)
            binding.homeSkeleton.updatePadding(top = top, bottom = bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.homeList)
    }

    private fun rebuild() {
        val context = context ?: return
        val cloud = viewModel.cloud.value
        val local = viewModel.local.value
        val recent = viewModel.recent.value
        val favorites = viewModel.favoriteTracks()
        val all = viewModel.allTracks()

        val loading = all.isEmpty() &&
            (viewModel.cloudLoading.value || viewModel.localLoading.value)
        if (!loading && skeletonShown) {
            // 数据可能在一个很长的首帧里就回来了；骨架至少留一段时间，
            // 并且同一时间只允许一个等待任务，避免后到的 rebuild 把骨架提前关掉。
            if (skeletonWaitScheduled) return
            val remaining = MIN_SKELETON_MS - (SystemClock.uptimeMillis() - skeletonSince)
            if (remaining > 0L) {
                skeletonWaitScheduled = true
                binding.homeList.postDelayed({
                    skeletonWaitScheduled = false
                    if (isAdded) rebuild()
                }, remaining)
                return
            }
        }
        if (loading) {
            showSkeleton()
            adapter.submitList(emptyList())
            return
        }

        val playlists = Playlists.build(context, cloud, local, recent, favorites)
        val recommendation = all.take(HOME_TRACK_COUNT)

        val items = mutableListOf<HomeItem>()
        items += HomeItem.Header(all.take(HERO_COUNT))

        if (playlists.isNotEmpty()) {
            items += HomeItem.Section(
                title = getString(R.string.home_playlists),
                action = getString(R.string.home_more_playlists),
                actionTag = HomeAdapter.ACTION_MORE_PLAYLISTS,
            )
            items += HomeItem.PlaylistRow(playlists)
        }

        if (recommendation.isNotEmpty()) {
            items += HomeItem.Section(
                title = getString(R.string.home_good_songs),
                subtitle = getString(R.string.home_good_songs_sub),
                action = getString(R.string.home_play_all),
                actionTag = HomeAdapter.ACTION_PLAY_ALL,
            )
            recommendation.forEach { items += HomeItem.TrackRow(it) }
        }

        if (all.isEmpty() && !viewModel.cloudLoading.value) {
            items += HomeItem.Empty(getString(R.string.home_empty))
        }

        val playEntrance = !entrancePlayed && all.isNotEmpty()
        if (playEntrance) entrancePlayed = true
        adapter.submitList(items) {
            // 等列表真的有子项了再撤骨架：否则会先露出空白，再"啪"地出现终态。
            binding.homeList.doOnNextLayout {
                binding.homeList.post { revealHome(playEntrance) }
            }
            binding.homeList.requestLayout()
        }
    }

    private fun revealHome(playEntrance: Boolean) {
        val list = binding.homeList
        if (!isAdded) return
        if (list.childCount == 0) {
            list.post { revealHome(playEntrance) }
            return
        }
        if (playEntrance) adapter.prepareEntrance(list)
        hideSkeleton()
        if (playEntrance) adapter.playEntrance(list)
    }

    private fun showSkeleton() {
        if (!skeletonShown) skeletonSince = SystemClock.uptimeMillis()
        skeletonShown = true
        binding.homeSkeleton.animate().cancel()
        binding.homeSkeleton.alpha = 1f
        binding.homeSkeleton.visibility = View.VISIBLE
        binding.homeList.visibility = View.INVISIBLE
    }

    private fun hideSkeleton() {
        if (!skeletonShown) {
            binding.homeList.visibility = View.VISIBLE
            return
        }
        skeletonShown = false
        binding.homeList.visibility = View.VISIBLE
        // 骨架最后淡出，而不是直接消失：内容正在从下方进场，硬切会留出一帧空白。
        binding.homeSkeleton.animate().cancel()
        if (Motion.animationsDisabled(requireContext())) {
            binding.homeSkeleton.visibility = View.GONE
            binding.homeSkeleton.alpha = 1f
            return
        }
        binding.homeSkeleton.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                binding.homeSkeleton.visibility = View.GONE
                binding.homeSkeleton.alpha = 1f
            }
            .start()
    }

    private fun playTrack(track: MusicTrack) {
        val queue = viewModel.allTracks()
        val index = queue.indexOfFirst { it.key == track.key }
        viewModel.play(queue, index.coerceAtLeast(0))
    }

    private fun handleQuick(action: QuickAction) {
        when (action) {
            QuickAction.FAVORITE -> TrackListActivity.startFavorites(requireContext())
            QuickAction.LOCAL -> mainActivity()?.openLocalTab()
            QuickAction.DAILY, QuickAction.CHART, QuickAction.ARTIST ->
                mainActivity()?.openRecommendTab()
        }
    }

    private fun handleSectionAction(tag: String) {
        when (tag) {
            HomeAdapter.ACTION_MORE_PLAYLISTS -> mainActivity()?.openRecommendTab()
            HomeAdapter.ACTION_PLAY_ALL -> {
                val queue = viewModel.allTracks().take(HOME_TRACK_COUNT)
                viewModel.play(queue, 0)
            }
        }
    }

    private fun openSearch() {
        startActivity(Intent(requireContext(), SearchActivity::class.java))
    }

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.mine_rescan),
            getString(R.string.mine_about_title),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.refreshLocal()
                        requireContext().toast(R.string.local_rescanned)
                    }

                    else -> showAbout(requireContext())
                }
            }
            .show()
    }

    private fun mainActivity(): MainActivity? = activity as? MainActivity

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val HERO_COUNT = 3
        const val HOME_TRACK_COUNT = 6
        const val MIN_SKELETON_MS = 800L
    }
}