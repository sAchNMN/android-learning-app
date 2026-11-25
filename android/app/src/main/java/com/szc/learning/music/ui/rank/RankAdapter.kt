package com.szc.learning.music.ui.rank

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.network.music.RankedTrack
import com.szc.learning.databinding.ItemRankTrackBinding
import com.szc.learning.music.ui.common.bindArt
import com.szc.learning.ui.theme.themeColor

/**
 * 榜单列表。
 *
 * 前三名用强调色，其余退回次级色 —— 名次本身已经排好序了，
 * 再给整列染色只会让人看不清歌名。
 *
 * 右侧那个数字在两种榜里含义不同：热歌榜是热度分，飙升榜是增量。
 * 同一个位置放两种量，靠正负号区分：只有飙升榜会出现 `↑` 开头的数。
 */
class RankAdapter(
    private val rising: Boolean,
    private val onClick: (index: Int) -> Unit,
) : ListAdapter<RankedTrack, RankAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRankTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemRankTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RankedTrack) {
            val context = binding.root.context
            binding.rankIndex.text = item.rank.toString()
            binding.rankIndex.setTextColor(
                context.themeColor(
                    if (item.rank <= TOP_RANKS) R.attr.auAccent else R.attr.auInkSecondary,
                ),
            )
            binding.rankTitle.text = item.track.title
            binding.rankArtist.text = item.track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.rankCover.bindArt(item.track, sizePx = 200)

            if (rising) {
                binding.rankScore.text = when {
                    item.delta > 0 -> context.getString(R.string.rank_delta_up, item.delta)
                    item.delta < 0 -> context.getString(R.string.rank_delta_down, -item.delta)
                    else -> ""
                }
                binding.rankScore.setTextColor(
                    context.themeColor(
                        if (item.delta > 0) R.attr.auAccent else R.attr.auInkSecondary,
                    ),
                )
            } else {
                binding.rankScore.text = context.getString(R.string.rank_score, item.score)
                binding.rankScore.setTextColor(context.themeColor(R.attr.auInkSecondary))
            }
            // 数字为空时留着占位不闪：整行高度固定，隐不隐藏都一样
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
        }
    }

    private companion object {
        const val TOP_RANKS = 3

        val DIFF = object : DiffUtil.ItemCallback<RankedTrack>() {
            override fun areItemsTheSame(oldItem: RankedTrack, newItem: RankedTrack): Boolean =
                oldItem.track.key == newItem.track.key

            override fun areContentsTheSame(oldItem: RankedTrack, newItem: RankedTrack): Boolean =
                oldItem == newItem
        }
    }
}
