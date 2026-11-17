package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemCloudBinding
import com.szc.learning.databinding.ItemShelfBinding
import com.szc.learning.databinding.ItemTrackBinding
import com.szc.learning.music.ui.common.bindArt

/**
 * 同一种数据（[MusicTrack]）的三种排版：
 * - [TrackAdapter] 列表行（资料库、推荐、搜索结果）
 * - [ShelfAdapter] 横滑卡片（首页最近播放）
 * - [CloudAdapter] 网格（云端曲库）
 *
 * 三个都只做「渲染 + 抛点击索引」，播放决策留给 Fragment，
 * 这样同一个行布局能同时服务"整表播放"和"跳到某一首"两种语义。
 */

private val trackDiff = object : DiffUtil.ItemCallback<MusicTrack>() {
    override fun areItemsTheSame(oldItem: MusicTrack, newItem: MusicTrack): Boolean =
        oldItem.key == newItem.key

    override fun areContentsTheSame(oldItem: MusicTrack, newItem: MusicTrack): Boolean =
        oldItem == newItem
}

class TrackAdapter(
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<MusicTrack, TrackAdapter.Holder>(trackDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.trackTitle.text = track.title
            binding.trackArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""
            binding.trackCover.bindArt(track, sizePx = 160)
            binding.root.setOnClickListener {
                // 用 bindingAdapterPosition 而不是构造时捕获的 position：
                // 列表刷新后旧 position 会点错歌
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
        }
    }
}

class ShelfAdapter(
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<MusicTrack, ShelfAdapter.Holder>(trackDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemShelfBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemShelfBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.shelfTitle.text = track.title
            binding.shelfArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.shelfCover.bindArt(track, sizePx = 400)
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
        }
    }
}

class CloudAdapter(
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<MusicTrack, CloudAdapter.Holder>(trackDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCloudBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemCloudBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.cloudTitle.text = track.title
            binding.cloudArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.cloudCover.bindArt(track, sizePx = 400)
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
        }
    }
}
