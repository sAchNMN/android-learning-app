package com.szc.learning.music.ui.recommend

import android.content.Intent
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
import com.szc.learning.core.common.music.TrackSource
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
import com.szc.learning.music.ui.playlist.PlaylistDetailActivity
import com.szc.learning.music.ui.rank.RankActivity
import com.szc.learning.music.ui.search.SearchActivity
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.widget.ShimmerSkeletonView
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        onClick = { index -> playFrom(index) },
        onToggleFavorite = { track -> viewModel.toggleFavorite(track) },
    )

    /** 本机拼出来的歌单（收藏 / 云端 / 本地 / 最近 / 同歌手）。 */
    private val playlistAdapter = PlaylistRailAdapter { playlist ->
        viewModel.playTracks(playlist.tracks, 0, ::onPlayEvent)
    }

    /** 音源账号里的歌单：卡片只有标题/封面/总数，点开进详情页看曲目。 */
    private val sourceAdapter = PlaylistRailAdapter(
        onClick = { card ->
            val playlist = sourceIndex[card.key]
            if (playlist == null) {
                viewModel.playTracks(card.tracks, 0, ::onPlayEvent)
            } else {
                PlaylistDetailActivity.start(
                    context = requireContext(),
                    source = playlist.source,
                    id = playlist.id,
                    name = playlist.name,
                    coverUrl = playlist.coverUrl,
                    trackCount = playlist.trackCount,
                )
            }
        },
        // 这一轨点下去是进详情页，封面不挂播放三角
        showPlayBadge = false,
    )

    /** 卡片 key → 音源歌单，点卡片时用它回查 source/id。 */
    private val sourceIndex = mutableMapOf<String, UserPlaylistInfo>()

    /** 本机歌单重算的协程：新输入到来时取消上一次。 */
    private var playlistsJob: Job? = null

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
            // 列表高度是 wrap_content，任何 change 动画都会让外层 NestedScrollView
            // 连着整页重新测量；滚动时的观感由 Coil 的淡入负责，动画这里不需要
            itemAnimator = null
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
        binding.recommendRank.setOnClickListener {
            startActivity(Intent(requireContext(), RankActivity::class.java))
        }
        binding.recommendSearch.setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }
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
                    renderTracks()
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
                // 导入完分享码回到推荐页，新歌单要立刻出现，不用杀进程重进
                viewModel.sharedPlaylists.collect { renderPlaylists() }
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
        animateTrackList(delayMs = offset + 220L)
        binding.recommendSourceHeader.springEnter(delayMs = offset + 360L, distanceDp = 44f)
        binding.recommendSourcePlaylists.springEnter(delayMs = offset + 400L, distanceDp = 48f)
        binding.recommendPlaylistsHeader.springEnter(delayMs = offset + 460L, distanceDp = 44f)
        binding.recommendPlaylists.springEnter(delayMs = offset + 500L, distanceDp = 48f)
    }

    /** 空态提示延后淡入，既不和飞上来的标题叠影，也不会突兀地"啪"出现。 */
    private fun fadeEmptyHints() {
        listOf(binding.recommendEmpty).forEach { view ->
            if (!view.isVisible) return@forEach
            view.alpha = 0f
            view.animate().alpha(1f).setStartDelay(EMPTY_HINT_DELAY_MS).setDuration(220L).start()
        }
    }

    /**
     * 曲目列表整体入场。
     *
     * 以前这里是对 RecyclerView 的前 6 个 child 逐个 springEnter，那是 bug 根源：
     * springEnter 先把 child 写成 alpha=0，再靠带 startDelay 的动画推回终态；
     * 而 child 是 ViewHolder.itemView，会被后续 submitList（收藏、静默同步都会触发）
     * 重新绑定/拆装，动画中途被打断后 alpha=0 就永久留下。表现就是截图里那样：
     * 前 6 行透明看不见，没被动画碰过的第 7 行往后正常显示，像"下半部分没飞入"。
     *
     * 现在把整个列表当成一个稳定的 View 飞入，和上半部分同一套机制 —— child 怎么
     * 被复用都不影响它，所有行一起飞，也不再有"前 6 行 / 后面"的割裂。
     * 另外不再因为 userScrolled 直接跳过：整体动画只有一个 animator，开销可忽略，
     * 数据晚到时跳过只会让整个列表"啪"地出现，那正是要修的现象。
     */
    private fun animateTrackList(delayMs: Long, attempt: Int = 0) {
        if (rowsEntered) return
        binding.recommendTracks.post {
            val list = _binding?.recommendTracks ?: return@post
            if (list.childCount == 0) {
                // 数据刚提交、还没排版完：下一帧再试，最多重试三次
                if (attempt < ROW_ENTRANCE_RETRY) animateTrackList(delayMs, attempt + 1)
                return@post
            }
            rowsEntered = true
            if (Motion.animationsDisabled(requireContext())) return@post
            list.springEnter(delayMs = delayMs, distanceDp = 56f)
        }
    }

    /** 标题下只在离线时露一行状态；正常情况下内容自己说明一切。 */
    private fun renderHeader() {
        binding.recommendSubtitle.isVisible = viewModel.recommendOffline.value
    }

    private fun renderTracks() {
        // 红心状态随 item 一起提交，收藏变化时 DiffUtil 只标出变的那几行
        val liked = viewModel.favorites.value
        val tracks = viewModel.recommend.value
        // 只有混源时角标才提供信息；全是同一平台时不重复贴
        val showSource = tracks
            .filter { it.source == TrackSource.ONLINE }
            .map { it.sourceKey }
            .distinct()
            .size > 1
        val rows = tracks.map { track ->
            RecommendTrackAdapter.Row(track, track.key in liked, showSource)
        }
        // 提交回调里补播行入场：推荐结果是网络来的，可能在页面入场之后才到
        trackAdapter.submitList(rows) {
            animateTrackList(delayMs = 160L + MainActivity.entranceDelayMs())
        }

        renderEmpty()
    }

    /**
     * 推荐为空：收起每日卡，只留一行空态（刷新按钮就在标题旁）。
     *
     * 「加载中」和「加载失败」两个时机都要判断：失败时 `recommend` 不会发出新值。
     */
    private fun renderEmpty() {
        if (_binding == null) return
        val tracks = viewModel.recommend.value
        binding.recommendEmpty.isVisible = tracks.isEmpty() && !viewModel.recommendLoading.value
        binding.dailyCard.isVisible = tracks.isNotEmpty()
    }

    private fun renderSourcePlaylists() {
        val playlists = viewModel.syncedPlaylists.value
        sourceIndex.clear()
        playlists.forEach { sourceIndex["source:${it.source}:${it.id}"] = it }
        sourceAdapter.submitList(playlists.map { it.toCard() })
        // 没登录音源就整段不出现，不留一句「去登录」的说明
        binding.recommendSourceHeader.isVisible = playlists.isNotEmpty()
        binding.recommendSourcePlaylists.isVisible = playlists.isNotEmpty()
    }

    private fun renderPlaylists() {
        val context = context ?: return
        // 按歌手分组是整库计算，且每次切歌（recent 变化）都会重算 —— 挪到后台
        val local = viewModel.local.value
        val recent = viewModel.recent.value
        val favorites = viewModel.favoriteTracks()
        val shared = viewModel.sharedPlaylists.value
        playlistsJob?.cancel()
        playlistsJob = viewLifecycleOwner.lifecycleScope.launch {
            val playlists = withContext(Dispatchers.Default) {
                Playlists.build(
                    context.applicationContext,
                    local,
                    recent,
                    favorites,
                    shared,
                    limit = 8,
                )
            }
            playlistAdapter.submitList(playlists)
            binding.recommendPlaylistsHeader.isVisible = playlists.isNotEmpty()
            binding.recommendPlaylists.isVisible = playlists.isNotEmpty()
        }
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
        /** 列表还没排版好时的补播重试次数。 */
        const val ROW_ENTRANCE_RETRY = 3

        /** 空态文案延后淡入的时间点，落在分区标题落定之后。 */
        const val EMPTY_HINT_DELAY_MS = 640L
    }
}
