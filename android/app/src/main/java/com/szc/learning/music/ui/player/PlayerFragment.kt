package com.szc.learning.music.ui.player

import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.szc.learning.R
import com.szc.learning.core.common.HudToast
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.FragmentPlayerBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.music.ui.comments.CommentSheet
import com.szc.learning.music.ui.common.iconRes
import com.szc.learning.music.ui.common.labelRes
import com.szc.learning.music.ui.common.showQueueSheet
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.themeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放器 tab：整屏竖向翻页，上滑/下滑换歌 —— 刷短视频的那套手势。
 *
 * 页面列表就是**当前播放队列** [MusicViewModel.feed]：从推荐页/本地页/歌单点播的
 * 那批歌会原样出现在这里。只有用户把播放方式切到「歌曲推荐」、并且队列真正播到
 * 最后一首时，才往后接实时推荐（换歌手扩散 / 换推荐批次）。其余模式播完就停或
 * 循环，不会偷偷塞歌进来。
 *
 * 音频始终由全局 PlayerController 播放，翻页只是把当前曲目切过去，
 * 所以离开这个 tab 后歌不会停。
 */
class PlayerFragment : Fragment(R.layout.fragment_player) {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private var tracks: List<MusicTrack> = emptyList()
    private var activeTrackKey: String? = null
    private var lyricsJob: Job? = null

    /** 「暂时拿不到新歌」只提示一次；失败重试成功后要复位。 */
    private var feedEndNotified = false

    private val adapter: PlayerAdapter = PlayerAdapter(
        favoriteKeys = { viewModel.favorites.value },
        onToggleFavorite = { track ->
            val favorite = viewModel.toggleFavorite(track)
            adapter.setFavorites(viewModel.favorites.value)
            toast(if (favorite) R.string.player_added_favorite else R.string.player_removed_favorite)
        },
        onTogglePlay = { viewModel.toggle() },
        onPrevious = { viewModel.previous() },
        onNext = { viewModel.next() },
        onCyclePlayMode = {
            // 用返回值而不是回读 state：state 由 media3 回调驱动，这一帧还是旧模式
            viewModel.cyclePlayMode()?.let { toast(it.labelRes, it.iconRes) }
        },
        onSeek = { fraction -> viewModel.seekToFraction(fraction) },
        onSeekTo = { positionMs -> viewModel.seekTo(positionMs) },
        onShowLyrics = { track, text -> showLyrics(track, text) },
        onShowQueue = { showQueueSheet(requireActivity(), viewModel) },
        onDislike = { track ->
            viewModel.dislike(track)
            toast(R.string.player_disliked)
        },
        onShowComments = { track -> CommentSheet(requireActivity(), viewModel, track).show() },
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerBinding.bind(view)

        binding.playerPager.apply {
            orientation = ViewPager2.ORIENTATION_VERTICAL
            offscreenPageLimit = 1
            adapter = this@PlayerFragment.adapter
            setPageTransformer { page, position ->
                page.alpha = 1f - abs(position).coerceIn(0f, 1f) * 0.45f
            }
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = activate(position)
                },
            )
        }

        binding.playerRoot.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val context = requireContext()
            adapter.setInsets(
                topPx = bars.top + context.dp(20f).toInt(),
                bottomPx = bars.bottom + context.dp(96f).toInt(),
            )
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.feed.collect { renderFeed(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.feedLoading.collect { renderEmptyHint() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.feedEnded.collect { ended ->
                    renderEmptyHint()
                    // 流到底了：给一次提示就够，别每次滑到末尾都弹
                    if (!ended) {
                        feedEndNotified = false
                        return@collect
                    }
                    if (feedEndNotified) return@collect
                    feedEndNotified = true
                    HudToast.show(
                        requireContext(),
                        getString(R.string.player_feed_ended),
                        bottomMarginDp = 276,
                    )
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.favorites.collect { adapter.setFavorites(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playerState.collect { state ->
                    adapter.setState(state)
                    if (isHidden) return@collect
                    val key = state.track?.key ?: return@collect
                    if (key == activeTrackKey) return@collect
                    val index = tracks.indexOfFirst { it.key == key }
                    if (index < 0) return@collect
                    activeTrackKey = key
                    if (binding.playerPager.currentItem != index) {
                        binding.playerPager.setCurrentItem(index, true)
                    } else {
                        adapter.setActive(index)
                    }
                }
            }
        }

        // 进度单独一条流：只推当前页的进度条与歌词高亮
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.position.collect { position -> adapter.setPosition(position) }
            }
        }

        // 从推荐页/歌单点播后切回来：先把流对齐到新的播放队列，再决定要不要建流
        viewModel.adoptQueueIfChanged()
        renderFeed(viewModel.feed.value)
        viewModel.startFeed()
    }

    /**
     * 流变化：可能是首批入流、往后接了一批、也可能是整条流被换掉。
     *
     * 前两种只改列表不动位置 —— 用户正滑到第 7 首，后面来了 20 首不该把他弹回去。
     * 只有「整条流换了」才需要追踪正在播放的那首。
     */
    private fun renderFeed(feed: List<MusicTrack>) {
        val oldKeys = tracks.map { it.key }
        val newKeys = feed.map { it.key }
        if (newKeys == oldKeys && feed == tracks) return

        // 旧列表是新列表的前缀 → 只追加（或原地更新了某一首的 uri）
        val prefixKept = newKeys.size >= oldKeys.size &&
            newKeys.subList(0, oldKeys.size) == oldKeys

        tracks = feed
        adapter.submit(feed)
        renderEmptyHint()
        if (feed.isEmpty()) return

        if (prefixKept && binding.playerPager.currentItem in feed.indices) {
            val index = binding.playerPager.currentItem
            adapter.setActive(index)
            if (oldKeys.isEmpty()) {
                // 首批入流：跟随正在播放的那首（ViewModel 已经把它插到流里了）；
                // 什么都没在播才从第一首起播，别打断用户在别处点的歌
                val playingKey = viewModel.playerState.value.track?.key
                val playingIndex = feed.indexOfFirst { it.key == playingKey }
                when {
                    playingIndex >= 0 -> {
                        if (binding.playerPager.currentItem != playingIndex) {
                            binding.playerPager.setCurrentItem(playingIndex, false)
                        }
                        activate(playingIndex)
                    }

                    playingKey == null -> activate(0, forcePlay = true)
                }
            }
            return
        }

        val playingKey = viewModel.playerState.value.track?.key
        val index = feed.indexOfFirst { it.key == playingKey }
        val start = if (index >= 0) index else 0
        binding.playerPager.setCurrentItem(start, false)
        activate(start, forcePlay = index < 0)
    }

    /** 空态：加载中说「正在选歌」，扩不出新歌才说拿不到。 */
    private fun renderEmptyHint() {
        if (_binding == null) return
        val empty = tracks.isEmpty()
        binding.playerEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (!empty) return
        binding.playerEmpty.setText(
            when {
                viewModel.feedEnded.value -> R.string.player_feed_ended
                else -> R.string.player_feed_loading
            },
        )
    }

    private fun activate(position: Int, forcePlay: Boolean = false) {
        if (tracks.isEmpty()) return
        val index = position.coerceIn(0, tracks.lastIndex)
        val track = tracks[index]
        activeTrackKey = track.key
        adapter.setActive(index)
        loadLyrics(track)
        // 滑到离末尾还有几首就补，等到最后一首才发请求一定会露出空窗
        if (index >= tracks.size - LOAD_MORE_THRESHOLD) viewModel.loadMoreFeed()

        val current = viewModel.playerState.value.track?.key
        if (forcePlay || current != track.key) {
            viewModel.playFeedAt(index) { event ->
                when (event) {
                    is OnlinePlayEvent.Ready -> adapter.setState(viewModel.playerState.value)
                    is OnlinePlayEvent.Failed -> {
                        if (isAdded) {
                            HudToast.show(requireContext(), event.message, bottomMarginDp = 276)
                        }
                    }
                }
            }
        } else {
            adapter.setState(viewModel.playerState.value)
        }
    }

    /**
     * 只解析当前页，再顺手预取前后页。本地歌第一次会读 ID3，
     * 预取让下一次上滑时歌词已经在了；歌词仓库本身有缓存，重复调用很便宜。
     */
    private fun loadLyrics(track: MusicTrack) {
        lyricsJob?.cancel()
        lyricsJob = viewLifecycleOwner.lifecycleScope.launch {
            adapter.setLyrics(track.key, viewModel.lyrics(track))
            val index = tracks.indexOfFirst { it.key == track.key }
            if (index < 0) return@launch
            listOf(index - 1, index + 1)
                .filter { it in tracks.indices }
                .forEach { neighborIndex ->
                    val neighbor = tracks[neighborIndex]
                    adapter.setLyrics(neighbor.key, viewModel.lyrics(neighbor))
                }
        }
    }

    private fun showLyrics(track: MusicTrack, lyrics: String) {
        val content = android.widget.TextView(requireContext()).apply {
            text = lyrics
            setTextColor(requireContext().themeColor(com.szc.learning.R.attr.auInk))
            textSize = 14f
            setLineSpacing(requireContext().dp(5f), 1f)
            setPadding(
                requireContext().dp(22f).toInt(),
                requireContext().dp(8f).toInt(),
                requireContext().dp(22f).toInt(),
                requireContext().dp(8f).toInt(),
            )
        }
        val scroll = android.widget.ScrollView(requireContext()).apply { addView(content) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(track.title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        adapter.setState(viewModel.playerState.value)
        val key = viewModel.playerState.value.track?.key
        val index = tracks.indexOfFirst { it.key == key }
        if (index >= 0 && binding.playerPager.currentItem != index) {
            binding.playerPager.setCurrentItem(index, false)
        }
        tracks.getOrNull(binding.playerPager.currentItem)?.let(::loadLyrics)
        // 隐藏期间可能在别处点播了新队列：切回来先对齐
        viewModel.adoptQueueIfChanged()
        // 队尾补一次；是否真的推荐由 ViewModel 的模式 + hasNext 闸门决定
        if (binding.playerPager.currentItem >= tracks.size - LOAD_MORE_THRESHOLD) {
            viewModel.loadMoreFeed()
        }
    }

    override fun onDestroyView() {
        lyricsJob?.cancel()
        adapter.release()
        _binding = null
        super.onDestroyView()
    }

    /** 底部是进度条 + 传输控制条，提示抬到进度条上方，别盖住播放键和进度。 */
    private fun toast(resId: Int, iconRes: Int = 0) {
        HudToast.show(requireContext(), getString(resId), iconRes, bottomMarginDp = 276)
    }

    private companion object {
        /** 离末尾还剩几首就补下一批：留出下一批解析地址的时间。 */
        const val LOAD_MORE_THRESHOLD = 3
    }
}