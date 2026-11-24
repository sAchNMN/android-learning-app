package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.databinding.ItemPlaylistCardBinding
import com.szc.learning.music.data.Playlist
import com.szc.learning.music.ui.common.bindArt

/**
 * 歌单卡横滑轨道。
 *
 * 本机拼出来的歌单（收藏 / 云端 / 本地 / 最近 / 同歌手）点一下整张开播 ——
 * 它们本来就是曲库的切片，用户知道里面是什么。
 * 音源账号里的歌单不一样：卡片上只有标题和总数，得点进详情页看曲目，
 * 那一层的跳转由页面自己处理，这里只负责抛点击。
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
            binding.root.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) onClick(playlist)
            }
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