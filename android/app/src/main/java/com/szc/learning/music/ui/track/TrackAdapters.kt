package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemTrackBinding
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.sourceShortLabel

/**
 * 歌曲行列表。搜索结果、本地歌曲、收藏、队列都用它。
 *
 * 只做「渲染 + 抛点击位置」，播放决策留给页面 ——
 * 同一个行布局要同时服务"整表播放"和"跳到某一首"两种语义。
 *
 * [onLongClick] 默认 null：只有自建歌单详情页这类需要行级操作的场景才传，
 * 传了才注册长按监听，正常列表不会因为长按没反应而误触反馈。
 *
 * 参数顺序不能动：`onClick` 必须排最后 —— 现有调用点大量使用尾随 lambda
 * `TrackAdapter { index -> ... }`，一旦它后面再挂一个函数参数，
 * 尾随 lambda 就会绑到那个参数上，编译直接炸。
 */
class TrackAdapter(
    private val activeKey: String? = null,
    private val onLongClick: ((MusicTrack) -> Unit)? = null,
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<MusicTrack, TrackAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.trackTitle.text = track.title
            binding.trackTitle.setTextColor(
                binding.root.context.themeColor(
                    if (track.key == activeKey) R.attr.auAccent else R.attr.auInk,
                ),
            )
            binding.trackArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""
            // 在线结果标平台；本地/云端不显示，列表保持干净
            val online = track.source == TrackSource.ONLINE
            binding.trackSource.isVisible = online
            if (online) {
                binding.trackSource.text = sourceShortLabel(binding.root.context, track.sourceKey)
            }
            binding.trackCover.bindArt(track, sizePx = 200)
            binding.root.setOnClickListener {
                // 用 bindingAdapterPosition 而不是构造时捕获的 position：
                // 列表刷新后旧 position 会点错歌
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
            val longPress = onLongClick
            binding.root.setOnLongClickListener {
                val index = bindingAdapterPosition
                if (longPress == null || index == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                longPress(getItem(index))
                true
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MusicTrack>() {
            override fun areItemsTheSame(oldItem: MusicTrack, newItem: MusicTrack): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: MusicTrack, newItem: MusicTrack): Boolean =
                oldItem == newItem
        }
    }
}