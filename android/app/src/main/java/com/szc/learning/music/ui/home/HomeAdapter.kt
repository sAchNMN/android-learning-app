package com.szc.learning.music.ui.home

import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.ItemHomeHeaderBinding
import com.szc.learning.databinding.ItemHomeRailBinding
import com.szc.learning.databinding.ItemSectionBinding
import com.szc.learning.databinding.ItemTrackBinding
import com.szc.learning.music.ui.common.CoverArt
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.track.ShelfAdapter

/** 首页条目。四种类型，一个适配器，一棵 RecyclerView。 */
sealed interface HomeItem {
    val key: String

    data class Header(val hero: MusicTrack?) : HomeItem {
        override val key: String get() = "header"
    }

    data class Section(
        val title: String,
        val action: String?,
        val actionTag: String,
    ) : HomeItem {
        override val key: String get() = "section:$actionTag"
    }

    data class Rail(val tracks: List<MusicTrack>) : HomeItem {
        override val key: String get() = "rail"
    }

    data class Row(val track: MusicTrack) : HomeItem {
        override val key: String get() = "row:${track.key}"
    }
}

class HomeAdapter(
    private val onHeroClick: () -> Unit,
    private val onHeroPlay: () -> Unit,
    private val onSectionAction: (String) -> Unit,
    private val onRailClick: (MusicTrack) -> Unit,
    private val onRowClick: (MusicTrack) -> Unit,
) : ListAdapter<HomeItem, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is HomeItem.Header -> TYPE_HEADER
        is HomeItem.Section -> TYPE_SECTION
        is HomeItem.Rail -> TYPE_RAIL
        is HomeItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderHolder(ItemHomeHeaderBinding.inflate(inflater, parent, false))
            TYPE_SECTION -> SectionHolder(ItemSectionBinding.inflate(inflater, parent, false))
            TYPE_RAIL -> RailHolder(ItemHomeRailBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemTrackBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is HomeItem.Header -> (holder as HeaderHolder).bind(item.hero)
            is HomeItem.Section -> (holder as SectionHolder).bind(item)
            is HomeItem.Rail -> (holder as RailHolder).bind(item.tracks)
            is HomeItem.Row -> (holder as RowHolder).bind(item.track)
        }
    }

    // ---------------------------------------------------------------- 主卡片

    inner class HeaderHolder(private val binding: ItemHomeHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            // 圆角靠父容器的 outline 裁，XML 里设不了（clipToOutline 是 API 31 的属性）
            binding.heroCard.clipToOutline = true
            binding.heroCard.setOnClickListener { onHeroClick() }
            binding.heroPlay.setOnClickListener { onHeroPlay() }
        }

        fun bind(track: MusicTrack?) {
            if (track == null) {
                binding.heroTitle.text = ""
                binding.heroArtist.text = ""
                return
            }
            binding.heroTitle.text = track.title
            binding.heroArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            val fallback = BitmapDrawable(binding.root.resources, CoverArt.of(track.key, 600))
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
    }

    // ---------------------------------------------------------------- 分区标题

    inner class SectionHolder(private val binding: ItemSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private var tag: String = ""

        fun bind(item: HomeItem.Section) {
            tag = item.actionTag
            binding.sectionTitle.text = item.title
            binding.sectionAction.text = item.action
            binding.sectionAction.visibility =
                if (item.action == null) android.view.View.GONE else android.view.View.VISIBLE
            binding.sectionAction.setOnClickListener { onSectionAction(tag) }
        }
    }

    // ---------------------------------------------------------------- 横滑

    inner class RailHolder(private val binding: ItemHomeRailBinding) :
        RecyclerView.ViewHolder(binding.root) {

        // 用字段存一份当前数据：适配器初始化时不能引用自己（会触发递归类型推断）
        private var boundTracks: List<MusicTrack> = emptyList()

        private val shelfAdapter: ShelfAdapter = ShelfAdapter { index ->
            boundTracks.getOrNull(index)?.let(onRailClick)
        }

        init {
            binding.railList.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.railList.adapter = shelfAdapter
            binding.railList.setHasFixedSize(true)
        }

        fun bind(tracks: List<MusicTrack>) {
            boundTracks = tracks
            shelfAdapter.submitList(tracks)
        }
    }

    // ---------------------------------------------------------------- 曲目行

    inner class RowHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.trackTitle.text = track.title
            binding.trackArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text = ""
            binding.trackCover.bindArt(track, sizePx = 160)
            binding.root.setOnClickListener { onRowClick(track) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SECTION = 1
        const val TYPE_RAIL = 2
        const val TYPE_ROW = 3

        val DIFF = object : DiffUtil.ItemCallback<HomeItem>() {
            override fun areItemsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: HomeItem, newItem: HomeItem): Boolean =
                oldItem == newItem
        }
    }
}
