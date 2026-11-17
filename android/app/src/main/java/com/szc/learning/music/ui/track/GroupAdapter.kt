package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.ItemTrackBinding
import com.szc.learning.music.ui.common.bindArt

/** 一个聚合行：一张专辑，或一位艺人，附带它下面的全部曲目。 */
data class TrackGroup(
    val key: String,
    val title: String,
    val subtitle: String,
    val tracks: List<MusicTrack>,
) {
    val coverTrack: MusicTrack? get() = tracks.firstOrNull()
}

/**
 * 资料库的「专辑 / 艺人」视图。
 *
 * 复用列表行的布局：封面取组内第一首的封面，副标题是艺人或曲目数，
 * 右侧数字位放曲目数 —— 比再写一套布局省事，也比空着好。
 */
class GroupAdapter(
    private val onClick: (TrackGroup) -> Unit,
) : ListAdapter<TrackGroup, GroupAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(group: TrackGroup) {
            binding.trackTitle.text = group.title
            binding.trackArtist.text = group.subtitle
            binding.trackDuration.text = group.tracks.size.toString()
            group.coverTrack?.let { binding.trackCover.bindArt(it, sizePx = 160) }
            binding.root.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClick(group)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<TrackGroup>() {
            override fun areItemsTheSame(oldItem: TrackGroup, newItem: TrackGroup): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: TrackGroup, newItem: TrackGroup): Boolean =
                oldItem == newItem
        }
    }
}
