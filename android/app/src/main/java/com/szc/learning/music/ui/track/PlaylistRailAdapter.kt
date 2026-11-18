package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.ui.theme.themeColor
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.ItemPlaylistCardBinding
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.data.PlaylistTag
import com.szc.learning.music.ui.common.bindArt

/**
 * 歌单卡横滑轨道。首页和推荐页共用。
 *
 * 点卡片直接把整张歌单塞进播放队列 —— 这个项目没有歌单详情页，
 * 点开再点播放对十首歌的规模来说是多一步。
 */
class PlaylistRailAdapter(
    private val onClick: (Playlist) -> Unit,
) : ListAdapter<Playlist, PlaylistRailAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPlaylistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemPlaylistCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: Playlist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistCount.text =
                binding.root.context.getString(R.string.playlist_tracks, playlist.size)
            // 同步回来的歌单没有曲目详情，封面是平台下发的 URL，包一层临时曲目交给 Coil
            val artTrack = playlist.cover ?: playlist.coverUrl?.let { url ->
                MusicTrack(
                    id = playlist.key,
                    title = playlist.title,
                    artist = "",
                    album = "",
                    durationMs = 0,
                    uri = "",
                    artUri = url,
                )
            }
            artTrack?.let { binding.playlistCover.bindArt(it, sizePx = 480) }
            bindTag(playlist.tag)
            binding.root.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClick(playlist)
            }
        }

        private fun bindTag(tag: PlaylistTag) {
            val context = binding.root.context
            val background: Int
            val textColor: Int
            val label: Int
            when (tag) {
                PlaylistTag.CLOUD -> {
                    background = R.drawable.bg_tag_blue
                    textColor = R.attr.auTagBrandText
                    label = R.string.tag_cloud
                }

                PlaylistTag.ARTIST -> {
                    background = R.drawable.bg_tag_blue
                    textColor = R.attr.auTagBrandText
                    label = R.string.tag_artist
                }

                PlaylistTag.RECENT -> {
                    background = R.drawable.bg_tag_warm
                    textColor = R.attr.auTagWarmText
                    label = R.string.tag_recent
                }

                PlaylistTag.FAVORITE -> {
                    background = R.drawable.bg_tag_warm
                    textColor = R.attr.auTagWarmText
                    label = R.string.tag_favorite
                }

                PlaylistTag.LOCAL -> {
                    background = R.drawable.bg_tag_gray
                    textColor = R.attr.auInkSecondary
                    label = R.string.tag_local
                }

                PlaylistTag.SOURCE -> {
                    background = R.drawable.bg_tag_blue
                    textColor = R.attr.auTagBrandText
                    label = R.string.tag_source
                }
            }
            binding.playlistTag.setBackgroundResource(background)
            binding.playlistTag.setTextColor(context.themeColor(textColor))
            binding.playlistTag.setText(label)
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Playlist>() {
            override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean =
                oldItem == newItem
        }
    }
}