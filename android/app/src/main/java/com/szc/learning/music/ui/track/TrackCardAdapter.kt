package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.ItemTrackCardBinding
import com.szc.learning.music.ui.common.bindArt

/** 横滑的竖版歌曲卡（我的页最近播放 / 账号页常听排行）。 */
class TrackCardAdapter(
    private val onClick: (MusicTrack) -> Unit,
    /** 附加在歌手后面的补充信息，比如账号页的「105 次」。返回 null 就不加。 */
    private val extraOf: ((MusicTrack) -> String?)? = null,
) : ListAdapter<MusicTrack, TrackCardAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTrackCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemTrackCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack) {
            binding.cardTitle.text = track.title
            binding.cardArtist.text = track.artist.ifBlank {
                binding.root.context.getString(R.string.player_unknown_artist)
            }
            val extra = extraOf?.invoke(track)
            binding.cardExtra.isVisible = !extra.isNullOrBlank()
            binding.cardExtra.text = extra
            binding.cardCover.bindArt(track, sizePx = 280)
            binding.root.setOnClickListener { onClick(track) }
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