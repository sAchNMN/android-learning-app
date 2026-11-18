package com.szc.learning.music.ui.home

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemEmptyBinding
import com.szc.learning.databinding.ItemHeroPageBinding
import com.szc.learning.databinding.ItemHomeHeaderBinding
import com.szc.learning.databinding.ItemPlaylistRailBinding
import com.szc.learning.databinding.ItemSectionBinding
import com.szc.learning.databinding.ItemTrackBinding
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.motion.installSpringPress
import com.szc.learning.music.ui.motion.springEnter
import com.szc.learning.music.ui.track.PlaylistRailAdapter
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.music.ui.widget.withAlpha

/** 首页快捷入口。Fragment 负责把每个动作翻译成"跳 tab / 开列表 / 播放"。 */
enum class QuickAction { DAILY, CHART, ARTIST, FAVORITE, LOCAL }

/**
 * 首页条目。
 *
 * 整个页面是一棵 RecyclerView，而不是 ScrollView 套若干个列表：
 * 嵌套可滚动控件在 wrap_content 约束下会被量成 0 高，且滚动体验割裂。
 */
sealed interface HomeItem {
    val key: String

    data class Header(val hero: List<MusicTrack>) : HomeItem {
        override val key: String get() = "header"
    }

    data class Section(
        val title: String,
        val subtitle: String? = null,
        val action: String? = null,
        val actionTag: String? = null,
    ) : HomeItem {
        override val key: String get() = "section:$title"
    }

    data class PlaylistRow(val playlists: List<Playlist>) : HomeItem {
        override val key: String get() = "playlists"
    }

    data class TrackRow(val track: MusicTrack) : HomeItem {
        override val key: String get() = "track:${track.key}"
    }

    data class Empty(val text: String) : HomeItem {
        override val key: String get() = "empty"
    }
}

class HomeAdapter(
    private val onSearch: () -> Unit,
    private val onMenu: () -> Unit,
    private val onVideo: () -> Unit,
    private val onQuick: (QuickAction) -> Unit,
    private val onHeroPlay: (MusicTrack) -> Unit,
    private val onSectionAction: (String) -> Unit,
    private val onPlaylist: (Playlist) -> Unit,
    private val onTrack: (MusicTrack) -> Unit,
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(DIFF) {

    /**
     * 骨架撤掉之前先把首屏子项摆到入场起点，避免"先闪一下终态、再从头进场"。
     */
    fun prepareEntrance(list: RecyclerView) {
        for (i in 0 until list.childCount.coerceAtMost(9)) {
            val child = list.getChildAt(i) ?: continue
            val holder = list.findViewHolderForAdapterPosition(
                list.getChildAdapterPosition(child),
            )
            if (holder is HeaderHolder) {
                holder.prepareEntrance()
            } else {
                child.alpha = 0f
                child.translationY = child.context.dp(56f)
            }
        }
    }

    /**
     * 首屏入场：只对当前可见的子项播放一次，之后收藏/最近变化重排不再抖。
     * 头部拆成标题/搜索/hero/快捷入口四段分镜，普通行按 position 错峰。
     */
    fun playEntrance(list: RecyclerView) {
        val childCount = list.childCount.coerceAtMost(9)
        for (i in 0 until childCount) {
            val child = list.getChildAt(i) ?: continue
            val position = list.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            val holder = list.findViewHolderForAdapterPosition(position)
            if (holder is HeaderHolder) {
                holder.playEntrance()
            } else if (position > 0) {
                child.springEnter(
                    delayMs = 90L + position * 40L,
                    distanceDp = 56f,
                )
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Header -> TYPE_HEADER
        is HomeItem.Section -> TYPE_SECTION
        is HomeItem.PlaylistRow -> TYPE_PLAYLISTS
        is HomeItem.TrackRow -> TYPE_TRACK
        is HomeItem.Empty -> TYPE_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_PLAYLISTS -> PlaylistHolder(ItemPlaylistRailBinding.inflate(inflater, parent, false))
            TYPE_TRACK -> TrackHolder(ItemTrackBinding.inflate(inflater, parent, false))
            else -> EmptyHolder(ItemEmptyBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Header -> (holder as HeaderHolder).bind(item.hero)
            is HomeItem.Section -> (holder as SectionHolder).bind(item)
            is HomeItem.PlaylistRow -> (holder as PlaylistHolder).bind(item.playlists)
            is HomeItem.TrackRow -> (holder as TrackHolder).bind(item.track)
            is HomeItem.Empty -> (holder as EmptyHolder).bind(item.text)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        (holder as? HeaderHolder)?.startAutoScroll()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as? HeaderHolder)?.stopAutoScroll()
        super.onViewDetachedFromWindow(holder)
    }

    // ---------------------------------------------------------------- 头部

    inner class HeaderHolder(
        private val binding: ItemHomeHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val heroAdapter = HeroPagerAdapter { track -> onHeroPlay(track) }
        private var dotsCount = -1
        private var entrancePlayed = false

        private val autoScroll = object : Runnable {
            override fun run() {
                val count = heroAdapter.itemCount
                if (count > 1) {
                    binding.heroPager.setCurrentItem(
                        (binding.heroPager.currentItem + 1) % count,
                        true,
                    )
                }
                binding.root.postDelayed(this, HERO_INTERVAL_MS)
            }
        }

        init {
            binding.heroCard.clipToOutline = true
            binding.heroPager.offscreenPageLimit = 1
            binding.heroPager.adapter = heroAdapter
            binding.heroPager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = renderDots(position)
                },
            )

            binding.headerSearch.setOnClickListener { onSearch() }
            binding.headerSearchBar.setOnClickListener { onSearch() }
            binding.headerMenu.setOnClickListener { onMenu() }
            binding.headerVideo.setOnClickListener { onVideo() }

            binding.quickDaily.setOnClickListener { onQuick(QuickAction.DAILY) }
            binding.quickChart.setOnClickListener { onQuick(QuickAction.CHART) }
            binding.quickArtist.setOnClickListener { onQuick(QuickAction.ARTIST) }
            binding.quickFavorite.setOnClickListener { onQuick(QuickAction.FAVORITE) }
            binding.quickLocal.setOnClickListener { onQuick(QuickAction.LOCAL) }

            listOf(
                binding.headerSearch,
                binding.headerMenu,
                binding.headerVideo,
            ).forEach { it.installSpringPress(0.9f) }
            binding.headerSearchBar.installSpringPress(0.975f)
            listOf(
                binding.quickDaily,
                binding.quickChart,
                binding.quickArtist,
                binding.quickFavorite,
                binding.quickLocal,
            ).forEach { it.installSpringPress(0.92f) }
        }

        /**
         * 头部的分镜入场：标题 → 搜索 → hero → 五个快捷入口。
         * 每一段都是独立的 spring，落地时有一点过冲，不是生硬的位移。
         */
        fun playEntrance() {
            if (entrancePlayed) return
            entrancePlayed = true
            binding.headerTitleRow.springEnter(delayMs = 20L, distanceDp = 36f)
            binding.headerSearchBar.springEnter(delayMs = 80L, distanceDp = 40f)
            binding.heroCard.springEnter(delayMs = 140L, distanceDp = 48f)
            listOf(
                binding.quickDaily,
                binding.quickChart,
                binding.quickArtist,
                binding.quickFavorite,
                binding.quickLocal,
            ).forEachIndexed { index, view ->
                view.springEnter(
                    delayMs = 195L + index * 34L,
                    distanceDp = 34f,
                    durationSeconds = 0.58f,
                )
            }
        }

        fun prepareEntrance() {
            binding.headerTitleRow.alpha = 0f
            binding.headerTitleRow.translationY = binding.root.context.dp(36f)
            binding.headerSearchBar.alpha = 0f
            binding.headerSearchBar.translationY = binding.root.context.dp(40f)
            binding.heroCard.alpha = 0f
            binding.heroCard.translationY = binding.root.context.dp(48f)
            listOf(
                binding.quickDaily,
                binding.quickChart,
                binding.quickArtist,
                binding.quickFavorite,
                binding.quickLocal,
            ).forEach { view ->
                view.alpha = 0f
                view.translationY = binding.root.context.dp(34f)
            }
        }

        fun bind(hero: List<MusicTrack>) {
            heroAdapter.submit(hero)
            if (binding.heroPager.currentItem != 0) binding.heroPager.setCurrentItem(0, false)
            renderDots(0)
        }

        private fun renderDots(selected: Int) {
            val count = heroAdapter.itemCount
            if (count != dotsCount) {
                dotsCount = count
                binding.heroDots.removeAllViews()
                repeat(count) {
                    val dot = View(binding.root.context)
                    binding.heroDots.addView(
                        dot,
                        android.widget.LinearLayout.LayoutParams(
                            binding.root.context.dp(5f).toInt(),
                            binding.root.context.dp(5f).toInt(),
                        ).apply { marginEnd = binding.root.context.dp(4f).toInt() },
                    )
                }
            }
            for (index in 0 until binding.heroDots.childCount) {
                val dot = binding.heroDots.getChildAt(index)
                val active = index == selected
                val context = binding.root.context
                dot.layoutParams = (dot.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    width = context.dp(if (active) 14f else 5f).toInt()
                    height = context.dp(5f).toInt()
                }
                dot.background = GradientDrawable().apply {
                    cornerRadius = context.dp(3f)
                    setColor(if (active) 0xFFFFFFFF.toInt() else withAlpha(0xFFFFFFFF.toInt(), 0.42f))
                }
            }
        }

        fun startAutoScroll() {
            stopAutoScroll()
            binding.root.postDelayed(autoScroll, HERO_INTERVAL_MS)
        }

        fun stopAutoScroll() {
            binding.root.removeCallbacks(autoScroll)
        }
    }

    // ---------------------------------------------------------------- 分区

    inner class SectionHolder(
        private val binding: ItemSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(section: HomeItem.Section) {
            binding.sectionTitle.text = section.title
            binding.sectionSubtitle.text = section.subtitle.orEmpty()
            binding.sectionSubtitle.visibility =
                if (section.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE

            val action = section.action
            binding.sectionActionWrap.visibility = if (action == null) View.GONE else View.VISIBLE
            binding.sectionAction.text = action.orEmpty()
            binding.sectionChevron.visibility =
                if (section.actionTag == ACTION_MORE_PLAYLISTS) View.VISIBLE else View.GONE
            binding.sectionActionWrap.setOnClickListener {
                section.actionTag?.let(onSectionAction)
            }
        }
    }

    // ---------------------------------------------------------------- 歌单

    inner class PlaylistHolder(
        private val binding: ItemPlaylistRailBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val railAdapter = PlaylistRailAdapter { playlist -> onPlaylist(playlist) }

        init {
            binding.playlistRail.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.playlistRail.adapter = railAdapter
            binding.playlistRail.setHasFixedSize(true)
        }

        fun bind(playlists: List<Playlist>) {
            railAdapter.submitList(playlists)
        }
    }

    // ---------------------------------------------------------------- 歌曲

    inner class TrackHolder(
        private val binding: ItemTrackBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.trackTitle.text = track.title
            binding.trackArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""
            binding.trackCover.bindArt(track, sizePx = 200)
            binding.root.setOnClickListener { onTrack(track) }
        }
    }

    inner class EmptyHolder(
        private val binding: ItemEmptyBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(text: String) {
            binding.emptyText.text = text
        }
    }

    // ---------------------------------------------------------------- 轮播

    private inner class HeroPagerAdapter(
        private val onPlay: (MusicTrack) -> Unit,
    ) : RecyclerView.Adapter<HeroHolder>() {

        private var items: List<MusicTrack> = emptyList()

        fun submit(list: List<MusicTrack>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size.coerceAtLeast(1)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroHolder =
            HeroHolder(
                ItemHeroPageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
                onPlay,
            )

        override fun onBindViewHolder(holder: HeroHolder, position: Int) {
            holder.bind(items.getOrNull(position), position)
        }
    }

    companion object {
        const val ACTION_MORE_PLAYLISTS = "more_playlists"
        const val ACTION_PLAY_ALL = "play_all"

        private const val TYPE_HEADER = 0
        private const val TYPE_SECTION = 1
        private const val TYPE_PLAYLISTS = 2
        private const val TYPE_TRACK = 3
        private const val TYPE_EMPTY = 4
        private const val HERO_INTERVAL_MS = 4_500L

        private val DIFF = object : DiffUtil.ItemCallback<HomeItem>() {
            override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
                oldItem == newItem
        }
    }
}

/** 轮播页的 ViewHolder。放在文件级，避免内嵌类参与泛型签名时的解析问题。 */
private class HeroHolder(
    private val binding: ItemHeroPageBinding,
    private val onPlay: (MusicTrack) -> Unit,
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(track: MusicTrack?, position: Int) {
        val context = binding.root.context
        if (track == null) {
            binding.heroCover.setImageDrawable(
                BitmapDrawable(context.resources, CoverArt.of("aurora:hero", 900)),
            )
            binding.heroLabel.setText(R.string.home_hero_label)
            binding.heroTitle.setText(R.string.home_hero_empty_title)
            binding.heroArtist.setText(R.string.home_hero_empty_sub)
            binding.heroPlay.visibility = View.GONE
            binding.root.setOnClickListener(null)
            return
        }

        binding.heroCover.bindArt(track, sizePx = 900)
        binding.heroLabel.setText(
            if (position % 2 == 0) R.string.home_hero_label else R.string.home_hero_mv,
        )
        binding.heroTitle.text = track.title
        binding.heroArtist.text = track.artist.ifBlank {
            context.getString(R.string.player_unknown_artist)
        }
        binding.heroPlay.visibility = View.VISIBLE
        binding.root.setOnClickListener { onPlay(track) }
        binding.heroPlay.setOnClickListener { onPlay(track) }
    }
}