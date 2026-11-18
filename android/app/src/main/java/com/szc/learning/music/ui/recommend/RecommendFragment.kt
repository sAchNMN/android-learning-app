package com.szc.learning.music.ui.recommend

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
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
import com.szc.learning.music.ui.motion.Motion
import com.szc.learning.music.ui.motion.springEnter
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
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

    // 首屏入场只播一次：切 tab 回来不再抖一遍
    private var entrancePlayed = false
    private var rowsEntered = false
    private var skeletonShown = false
    private var revealScheduled = false

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
        if (loading) showSkeleton() else reveal()
    }

    private fun showSkeleton() {
        if (skeletonShown) return
        skeletonShown = true
        binding.recommendSkeleton.animate().cancel()
        binding.recommendSkeleton.alpha = 1f
        binding.recommendSkeleton.visibility = View.VISIBLE
        binding.recommendScroll.visibility = View.INVISIBLE
    }

    /**
     * 撤骨架 + 首屏入场。
     *
     * 骨架用 180ms 淡出而不是直接消失 —— 内容正从下方飞入，硬切会空一帧。
     * 入场**不等启动页的 logo 动画播完**：两个动画并行跑，启动层最后 200ms
     * 淡出时露出来的就是已经在动的内容，而不是先看完 logo 再从头播一遍。
     */
    private fun reveal() {
        if (revealScheduled) return
        revealScheduled = true
        binding.recommendScroll.visibility = View.VISIBLE
        // 锚到真正要画出来的那一帧再起动画。
        // 慢设备首帧可能晚好几秒，提前 start() 会让动画的 startTime 过期，首帧直接落终态。
        binding.recommendScroll.doOnPreDraw {
            fadeOutSkeleton()
            // 数据可能是在骨架期间失败的，那时还没资格显示空态；这里补一次
            renderEmpty()
            playEntrance()
        }
    }

    /** 骨架 180ms 淡出：内容正从下方飞入，硬切会空一帧。 */
    private fun fadeOutSkeleton() {
        if (!skeletonShown) return
        skeletonShown = false
        val skeleton = _binding?.recommendSkeleton ?: return
        skeleton.animate().cancel()
        if (Motion.animationsDisabled(requireContext())) {
            skeleton.visibility = View.GONE
            skeleton.alpha = 1f
            return
        }
        skeleton.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                _binding?.recommendSkeleton?.apply {
                    visibility = View.GONE
                    alpha = 1f
                }
            }
            .start()
    }

    /**
     * 首屏入场：从上到下按顺序错峰，每一段同时做「从下方飞入」+「透明度渐显」。
     *
     * 用的是全项目同一套 [springEnter]（解析弹簧，落点带回弹），
     * 所以手感和之前首页入场完全一致，不是另写的一套动画。
     */
    private fun playEntrance() {
        if (entrancePlayed || !isAdded || _binding == null) return
        entrancePlayed = true
        if (Motion.animationsDisabled(requireContext())) return

        // 空态文案不参与位移：分区标题是"飞上来"的，让它跟着动会和下方文案叠影
        fadeEmptyHints()

        // 冷启动时对齐启动层的淡出起点：内容与 logo 动画重叠一小段，
        // 而不是「看完 logo 再从头播一遍」。热启动没有启动动画，offset 为 0。
        val offset = MainActivity.entranceDelayMs()
        binding.recommendHeader.springEnter(delayMs = offset + 20L, distanceDp = 36f)
        binding.recommendSubtitle.springEnter(delayMs = offset + 70L, distanceDp = 40f)
        binding.dailyCard.springEnter(delayMs = offset + 120L, distanceDp = 48f)
        binding.recommendTracksHeader.springEnter(delayMs = offset + 180L, distanceDp = 44f)
        animateRows(delayMs = offset + 220L)
        binding.recommendSourceHeader.springEnter(delayMs = offset + 360L, distanceDp = 44f)
        binding.recommendSourcePlaylists.springEnter(delayMs = offset + 400L, distanceDp = 48f)
        binding.recommendPlaylistsHeader.springEnter(delayMs = offset + 460L, distanceDp = 44f)
        binding.recommendPlaylists.springEnter(delayMs = offset + 500L, distanceDp = 48f)
        binding.recommendFooter.springEnter(delayMs = offset + 560L, distanceDp = 36f)
    }

    /** 空态提示延后淡入，既不和飞上来的标题叠影，也不会突兀地"啪"出现。 */
    private fun fadeEmptyHints() {
        listOf(binding.recommendEmpty, binding.recommendSourceEmpty).forEach { view ->
            if (!view.isVisible) return@forEach
            view.alpha = 0f
            view.animate().alpha(1f).setStartDelay(EMPTY_HINT_DELAY_MS).setDuration(220L).start()
        }
    }

    /**
     * 曲目行单独错峰。
     *
     * 行是异步来的（推荐结果走网络），页面入场时可能一条都还没有，
     * 所以列表第一次真的出现子项时补播一次；已经播过就不再动。
     */
    private fun animateRows(delayMs: Long, attempt: Int = 0) {
        if (rowsEntered) return
        binding.recommendTracks.post {
            val list = _binding?.recommendTracks ?: return@post
            if (list.childCount == 0) {
                // 数据刚提交、还没排版完：下一帧再试，最多重试三次
                if (attempt < ROW_ENTRANCE_RETRY) animateRows(delayMs, attempt + 1)
                return@post
            }
            rowsEntered = true
            if (Motion.animationsDisabled(requireContext())) return@post
            val count = minOf(list.childCount, ROW_ENTRANCE_MAX)
            for (index in 0 until count) {
                list.getChildAt(index)?.springEnter(
                    delayMs = delayMs + index * ROW_STAGGER_MS,
                    distanceDp = 56f,
                )
            }
        }
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
        // 提交回调里补播行入场：推荐结果是网络来的，可能在页面入场之后才到
        trackAdapter.submitList(tracks) {
            animateRows(delayMs = 160L + MainActivity.entranceDelayMs())
        }
        binding.recommendTrackCount.text =
            getString(R.string.recommend_track_count, tracks.size)
        binding.dailySubtitle.setText(R.string.recommend_daily_subtitle)
        binding.dailyCard.isEnabled = tracks.isNotEmpty()

        renderEmpty()
    }

    /**
     * 推荐为空时的说明文案。
     *
     * 单独成一个方法是因为「加载中」和「加载失败」两个时机都要判断：
     * 失败时 `recommend` 压根不会发出新值，光靠 [renderTracks] 的话这段文案永远不会出现。
     */
    private fun renderEmpty() {
        if (_binding == null) return
        val empty = viewModel.recommend.value.isEmpty() && !viewModel.recommendLoading.value
        binding.recommendEmpty.isVisible = empty
        if (empty) {
            binding.recommendEmpty.text = buildString {
                append(getString(R.string.recommend_empty))
                append('\n')
                append(getString(R.string.recommend_empty_hint))
            }
        }
        // 没数据时大卡也别写「今天为你挑了 0 首」，那句话看着像出了 bug
        binding.dailyTitle.text = if (empty) {
            getString(R.string.recommend_empty)
        } else {
            getString(R.string.recommend_daily_title, viewModel.recommend.value.size)
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

    private companion object {
        /** 行入场最多错峰多少条，往下滑到的行不再补动画。 */
        const val ROW_ENTRANCE_MAX = 6

        /** 相邻两行的入场间隔，和旧首页 40ms 的节奏一致。 */
        const val ROW_STAGGER_MS = 40L

        /** 列表还没排版好时的补播重试次数。 */
        const val ROW_ENTRANCE_RETRY = 3

        /** 空态文案延后淡入的时间点，落在分区标题落定之后。 */
        const val EMPTY_HINT_DELAY_MS = 640L
    }
}
