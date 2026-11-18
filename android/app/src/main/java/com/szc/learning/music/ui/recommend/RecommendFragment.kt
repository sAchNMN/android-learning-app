package com.szc.learning.music.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
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
import com.szc.learning.core.network.music.UserPlaylistInfo
import com.szc.learning.databinding.FragmentRecommendBinding
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.data.PlaylistTag
import com.szc.learning.music.ui.MusicViewModel
import com.szc.learning.music.ui.OnlinePlayEvent
import com.szc.learning.music.ui.common.Playlists
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import kotlinx.coroutines.launch

/**
 * 推荐页。
 *
 * 数据不再来自「把本机曲库按天洗牌」——那是假推荐，换个日期就露馅。
 * 现在整条链路是：
 *
 * ```
 * 本机算口味（收藏 + 最近播放里的歌手）
 *        ↓ artists
 * 后端 /app/music/recommend：按歌手搜各音源 + 补平台推荐流 + 排除已喜欢
 *        ↓ OnlineTrack 列表
 * 页面渲染；结果同时写进本机缓存，断网时回放上一次
 * ```
 *
 * 每行的红心走「本机 → 后端 → 音源平台」三级同步，状态提示在下方 toast；
 * 「来自音源」那一排是账号里的歌单，进页面时自动同步（5 分钟内不重复请求）。
 */
class RecommendFragment : Fragment(R.layout.fragment_recommend) {

    private var _binding: FragmentRecommendBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val viewModel: MusicViewModel by lazy {
        ViewModelProvider(requireActivity())[MusicViewModel::class.java]
    }

    private val trackAdapter = RecommendTrackAdapter(
        favoriteKeys = { viewModel.favorites.value },
        onClick = { index -> playFrom(index) },
        onToggleFavorite = { track -> viewModel.toggleFavorite(track) },
    )

    /** 本机拼出来的歌单（收藏 / 云端 / 本地 / 最近 / 同歌手）。 */
    private val playlistAdapter = PlaylistRailAdapter { playlist ->
        viewModel.playTracks(playlist.tracks, 0, ::onPlayEvent)
    }

    /** 音源账号里的歌单：卡片只有标题/封面/总数，点开才去拉曲目。 */
    private val sourceAdapter = PlaylistRailAdapter { card ->
        val playlist = sourceIndex[card.key]
        if (playlist == null) {
            viewModel.playTracks(card.tracks, 0, ::onPlayEvent)
        } else {
            viewModel.playSyncedPlaylist(playlist, ::onPlayEvent)
        }
    }

    /** 卡片 key → 音源歌单，点卡片时用它回查 source/id。 */
    private val sourceIndex = mutableMapOf<String, UserPlaylistInfo>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentRecommendBinding.bind(view)

        binding.recommendSkeleton.style = ShimmerSkeletonView.Style.RECOMMEND

        binding.recommendTracks.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trackAdapter
            setHasFixedSize(false)
        }
        binding.recommendPlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = playlistAdapter
            setHasFixedSize(true)
        }
        binding.recommendSourcePlaylists.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = sourceAdapter
            setHasFixedSize(true)
        }

        binding.recommendRefresh.setOnClickListener { viewModel.loadRecommendations(refresh = true) }
        binding.dailyCard.setOnClickListener { playFrom(0) }
        binding.dailyPlay.setOnClickListener { playFrom(0) }
        installInsets()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommend.collect { renderTracks() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendBasedOn.collect { renderHeader() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendLoading.collect { renderLoading() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recommendOffline.collect { renderHeader() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.syncedPlaylists.collect {
                    renderSourcePlaylists()
                    renderPlaylists()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 收藏变了红心要跟着变；曲库变了本机歌单也要重算
                viewModel.favorites.collect {
                    trackAdapter.notifyDataSetChanged()
                    renderPlaylists()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.recent.collect { renderPlaylists() }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.likeNotice.collect { message -> context?.toast(message) }
            }
        }

        // 首次进入才拉数据；切回来不重复请求（ViewModel 里有 TTL 兜底）
        viewModel.loadRecommendations()
        viewModel.syncLibrary()
        renderAll()
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

    // ---------------------------------------------------------------- 渲染

    private fun renderAll() {
        renderLoading()
        renderHeader()
        renderTracks()
        renderSourcePlaylists()
        renderPlaylists()
    }

    private fun renderLoading() {
        val loading = viewModel.recommendLoading.value && viewModel.recommend.value.isEmpty()
        binding.recommendSkeleton.isVisible = loading
        binding.recommendScroll.isVisible = !loading
    }

    private fun renderHeader() {
        val offline = viewModel.recommendOffline.value
        val basedOn = viewModel.recommendBasedOn.value
        binding.recommendSubtitle.text = when {
            offline -> getString(R.string.recommend_subtitle_offline)
            basedOn.isNotEmpty() -> getString(
                R.string.recommend_subtitle_taste,
                basedOn.take(3).joinToString("、"),
            )

            else -> getString(R.string.recommend_subtitle_fresh)
        }
    }

    private fun renderTracks() {
        val tracks = viewModel.recommend.value
        trackAdapter.submitList(tracks)
        binding.recommendTrackCount.text =
            getString(R.string.recommend_track_count, tracks.size)
        binding.dailyTitle.text = getString(R.string.recommend_daily_title, tracks.size)
        binding.dailySubtitle.setText(R.string.recommend_daily_subtitle)
        binding.dailyCard.isEnabled = tracks.isNotEmpty()

        val empty = tracks.isEmpty() && !viewModel.recommendLoading.value
        binding.recommendEmpty.isVisible = empty
        if (empty) {
            binding.recommendEmpty.text = buildString {
                append(getString(R.string.recommend_empty))
                append('\n')
                append(getString(R.string.recommend_empty_hint))
            }
        }
    }

    private fun renderSourcePlaylists() {
        val playlists = viewModel.syncedPlaylists.value
        sourceIndex.clear()
        playlists.forEach { sourceIndex["source:${it.source}:${it.id}"] = it }
        sourceAdapter.submitList(playlists.map { it.toCard() })
        binding.recommendSourceState.text =
            getString(R.string.recommend_playlist_count, playlists.size)
        binding.recommendSourceEmpty.isVisible = playlists.isEmpty()
        binding.recommendSourcePlaylists.isVisible = playlists.isNotEmpty()
    }

    private fun renderPlaylists() {
        val context = context ?: return
        val playlists = Playlists.build(
            context,
            viewModel.cloud.value,
            viewModel.local.value,
            viewModel.recent.value,
            viewModel.favoriteTracks(),
            limit = 8,
        )
        playlistAdapter.submitList(playlists)
        binding.recommendMorePlaylists.text =
            getString(R.string.recommend_playlist_count, playlists.size)
    }

    // ---------------------------------------------------------------- 播放

    private fun playFrom(index: Int) {
        val tracks = viewModel.recommend.value
        if (tracks.isEmpty()) return
        viewModel.playTracks(tracks, index, ::onPlayEvent)
    }

    private fun onPlayEvent(event: OnlinePlayEvent) {
        when (event) {
            is OnlinePlayEvent.Ready -> context?.toast(event.notice)
            is OnlinePlayEvent.Failed -> context?.toast(event.message)
        }
    }

    /** 音源歌单 → 卡片模型：只有标题/封面/总数，曲目点开才拉。 */
    private fun UserPlaylistInfo.toCard(): Playlist = Playlist(
        key = "source:$source:$id",
        title = name,
        tag = PlaylistTag.SOURCE,
        tracks = emptyList<MusicTrack>(),
        declaredSize = trackCount,
        coverUrl = coverUrl,
    )

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
