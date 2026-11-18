package com.szc.learning.music.ui.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.toast
import com.szc.learning.databinding.FragmentPlayerBinding
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.themeColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 播放器 tab：整屏竖向翻页，上滑/下滑换歌。
 *
 * 旧 MV 页的定位是「短视频流」；现在没有 MV 素材，也不再假装有视频。
 * 这里回到音乐播放器本身：大封面、内嵌歌词、进度和传输控制。
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

    private val adapter: PlayerAdapter = PlayerAdapter(
        favoriteKeys = { viewModel.favorites.value },
        onToggleFavorite = { track ->
            val favorite = viewModel.toggleFavorite(track)
            adapter.setFavorites(viewModel.favorites.value)
            toast(if (favorite) R.string.player_added_favorite else R.string.player_removed_favorite)
        },
        onShare = { track -> share(track) },
        onMore = { track -> showMore(track) },
        onTogglePlay = { viewModel.toggle() },
        onPrevious = { viewModel.previous() },
        onNext = { viewModel.next() },
        onToggleShuffle = { viewModel.toggleShuffle() },
        onCycleRepeat = { viewModel.cycleRepeat() },
        onSeek = { fraction -> viewModel.seekToFraction(fraction) },
        onShowLyrics = { track, text -> showLyrics(track, text) },
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
            binding.playerHeader.updatePadding(top = bars.top + context.dp(8f).toInt())
            adapter.setInsets(
                topPx = bars.top + context.dp(58f).toInt(),
                bottomPx = bars.bottom + context.dp(96f).toInt(),
            )
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.cloud, viewModel.local) { _, _ -> Unit }
                    .collect { rebuildTracks() }
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
                    updatePageCount(index)
                }
            }
        }

        rebuildTracks()
    }

    private fun rebuildTracks() {
        val all = viewModel.allTracks()
        if (all.map { it.key } == tracks.map { it.key }) return

        val playingKey = viewModel.playerState.value.track?.key
        tracks = all
        adapter.submit(all)
        binding.playerEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        if (all.isEmpty()) {
            binding.playerPageCount.text = ""
            return
        }

        val index = all.indexOfFirst { it.key == playingKey }
        val start = if (index >= 0) index else 0
        binding.playerPager.setCurrentItem(start, false)
        activate(start, forcePlay = index < 0)
    }

    private fun activate(position: Int, forcePlay: Boolean = false) {
        if (tracks.isEmpty()) return
        val index = position.coerceIn(0, tracks.lastIndex)
        val track = tracks[index]
        activeTrackKey = track.key
        adapter.setActive(index)
        updatePageCount(index)
        loadLyrics(track)

        val current = viewModel.playerState.value.track?.key
        if (forcePlay || current != track.key) {
            viewModel.play(tracks, index)
        } else {
            adapter.setState(viewModel.playerState.value)
        }
    }

    private fun updatePageCount(index: Int) {
        binding.playerPageCount.text = getString(
            R.string.player_page_count,
            index + 1,
            tracks.size,
        )
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

    private fun share(track: MusicTrack) {
        val text = getString(
            R.string.player_share_text,
            track.title,
            track.artist.ifBlank { getString(R.string.player_unknown_artist) },
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, getString(R.string.player_share)))
    }

    private fun showMore(track: MusicTrack) {
        val favorite = viewModel.isFavorite(track.key)
        val actions = arrayOf(
            getString(if (favorite) R.string.player_unlike else R.string.player_like),
            getString(R.string.player_share),
        )
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        viewModel.toggleFavorite(track)
                        adapter.setFavorites(viewModel.favorites.value)
                    }

                    else -> share(track)
                }
            }
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
    }

    override fun onDestroyView() {
        lyricsJob?.cancel()
        adapter.release()
        _binding = null
        super.onDestroyView()
    }

    private fun toast(resId: Int) {
        requireContext().toast(resId)
    }
}