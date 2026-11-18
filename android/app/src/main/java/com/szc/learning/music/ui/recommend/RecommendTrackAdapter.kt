package com.szc.learning.music.ui.recommend

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemRecommendTrackBinding
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.music.ui.common.sourceShortLabel
import com.szc.learning.ui.theme.themeColor

/**
 * 推荐曲目列表。
 *
 * 与通用 [com.szc.learning.music.ui.track.TrackAdapter] 的区别只有一处：
 * 每行带一颗红心，点一下就走「本机写入 → 后端 → 音源平台」的同步链路。
 * 红心状态由外部传入（收藏集合可能被别的页面改掉），所以每次点击都通知重绑一行。
 */
class RecommendTrackAdapter(
    private val favoriteKeys: () -> Set<String>,
    private val onClick: (index: Int) -> Unit,
    private val onToggleFavorite: (MusicTrack) -> Unit,
) : ListAdapter<MusicTrack, RecommendTrackAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRecommendTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemRecommendTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            val context = binding.root.context
            binding.trackTitle.text = track.title
            binding.trackArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""

            val online = track.source == TrackSource.ONLINE
            binding.trackSource.isVisible = online
            if (online) binding.trackSource.text = sourceShortLabel(context, track.sourceKey)

            binding.trackCover.bindArt(track, sizePx = 220)

            val liked = track.key in favoriteKeys()
            binding.trackLike.setImageResource(
                if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
            )
            binding.trackLike.setColorFilter(
                context.themeColor(if (liked) R.attr.auAccent else R.attr.auInkSecondary),
            )
            binding.trackLike.contentDescription = context.getString(
                if (liked) R.string.player_unlike else R.string.recommend_like,
            )
            binding.trackLike.setOnClickListener {
                onToggleFavorite(track)
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
            }

            binding.root.setOnClickListener {
                // 用 bindingAdapterPosition：列表刷新后构造时捕获的 position 会点错歌
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
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
