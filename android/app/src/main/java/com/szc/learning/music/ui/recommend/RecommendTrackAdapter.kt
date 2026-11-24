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
import com.szc.learning.music.ui.widget.dp
import com.szc.learning.ui.theme.themeColor

/**
 * 推荐曲目列表。
 *
 * 与通用 [com.szc.learning.music.ui.track.TrackAdapter] 的区别只有一处：
 * 每行带一颗红心，点一下就走「本机写入 → 后端 → 音源平台」的同步链路。
 *
 * 红心状态进了 item（[Row.liked]）而不是每次绑定时现查收藏集合，
 * 这样收藏一变就能走 DiffUtil 的 payload 分支：只重画那一行的心，
 * 不重绑封面、不触发整页 requestLayout。
 *
 * 为什么较真这一处：推荐页是 NestedScrollView 套 wrap_content 的 RecyclerView，
 * 这个组合不回收子项，整页高度由列表撑起来。以前收藏变化走
 * `notifyDataSetChanged()`，等于把 20 行全部重绑 + 整页重新测量，
 * 滑到中段时正好撞上后台静默同步回写收藏，就是那一下顿。
 */
class RecommendTrackAdapter(
    private val onClick: (index: Int) -> Unit,
    private val onToggleFavorite: (MusicTrack) -> Boolean,
) : ListAdapter<RecommendTrackAdapter.Row, RecommendTrackAdapter.Holder>(DIFF) {

    /** 一行 = 曲目 + 当前是否已收藏 + 是否需要平台角标。 */
    data class Row(
        val track: MusicTrack,
        val liked: Boolean,
        val showSource: Boolean,
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRecommendTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        val row = getItem(position)
        if (payloads.isEmpty()) {
            holder.bind(row)
        } else {
            holder.bindLike(row.liked)
        }
    }

    inner class Holder(private val binding: ItemRecommendTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            val track = row.track
            val context = binding.root.context
            binding.trackTitle.text = track.title
            binding.trackArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.trackDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""

            val online = track.source == TrackSource.ONLINE
            // 整批来自同一个平台时不重复贴角标；只有混源时它才是有效信息
            binding.trackSource.isVisible = online && row.showSource
            if (online && row.showSource) {
                binding.trackSource.text = sourceShortLabel(context, track.sourceKey)
            }

            // 首次绑定时视图还没量过，用 48dp 的真实尺寸，别拿 220px 去画一张 168px 的图
            binding.trackCover.bindArt(track, sizePx = context.dp(COVER_DP).toInt())

            bindLike(row.liked)

            binding.trackLike.setOnClickListener {
                // 先就地画成新状态：本机写盘是同步的，没有失败的可能。
                // 等 DiffUtil 回来再刷会让红心看起来慢半拍，手感很差。
                bindLike(onToggleFavorite(track))
            }

            binding.root.setOnClickListener {
                // 用 bindingAdapterPosition：列表刷新后构造时捕获的 position 会点错歌
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onClick(index)
            }
        }

        /** 只刷红心。收藏变化走这里，不动封面和文字。 */
        fun bindLike(liked: Boolean) {
            val context = binding.root.context
            binding.trackLike.setImageResource(
                if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart,
            )
            binding.trackLike.setColorFilter(
                context.themeColor(if (liked) R.attr.auAccent else R.attr.auInkSecondary),
            )
            binding.trackLike.contentDescription = context.getString(
                if (liked) R.string.player_unlike else R.string.recommend_like,
            )
        }
    }

    private companion object {
        /** 行内封面尺寸（dp），与 item_recommend_track.xml 里的 48dp 对齐。 */
        const val COVER_DP = 48f

        val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem.track.key == newItem.track.key

            override fun areContentsTheSame(oldItem: Row, newItem: Row): Boolean =
                oldItem == newItem

            /** 只有「收藏与否」变了就只刷红心；角标变化要整行重绑。 */
            override fun getChangePayload(oldItem: Row, newItem: Row): Any? =
                if (oldItem.track == newItem.track &&
                    oldItem.showSource == newItem.showSource
                ) {
                    PAYLOAD_LIKE
                } else {
                    null
                }
        }

        const val PAYLOAD_LIKE = "like"
    }
}
