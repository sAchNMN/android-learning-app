package com.szc.learning.music.ui.playlist

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.databinding.ItemLocalPlaylistBinding
import com.szc.learning.music.data.LocalPlaylist
import com.szc.learning.music.ui.common.bindArt

/**
 * 自建歌单列表。
 *
 * 封面取第一首的专辑图；空歌单没有封面可借，[bindArt] 会按曲目 key 生成渐变块 ——
 * 传进去的 [artTrack] 用歌单 id 当种子，保证同一个歌单每次进来颜色一致。
 */
class LocalPlaylistAdapter(
    private val onClick: (LocalPlaylist) -> Unit,
    private val onMenu: (LocalPlaylist) -> Unit,
) : RecyclerView.Adapter<LocalPlaylistAdapter.Holder>() {

    private var items: List<LocalPlaylist> = emptyList()

    fun submit(list: List<LocalPlaylist>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLocalPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemLocalPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(playlist: LocalPlaylist) {
            binding.playlistTitle.text = playlist.title
            binding.playlistCount.text = binding.root.context.getString(
                R.string.local_playlist_track_count,
                playlist.tracks.size,
            )
            binding.playlistCover.bindArt(artTrack(playlist), sizePx = 200)
            binding.root.setOnClickListener { onClick(playlist) }
            binding.playlistMore.setOnClickListener { onMenu(playlist) }
        }
    }

    /** 封面只认 artUri 和 key，其余字段用不着；空歌单时 key 由歌单 id 兜底。 */
    private fun artTrack(playlist: LocalPlaylist): MusicTrack {
        val first = playlist.tracks.firstOrNull()
        return MusicTrack(
            id = first?.id ?: playlist.id,
            title = playlist.title,
            artist = first?.artist.orEmpty(),
            album = "",
            durationMs = 0,
            uri = "",
            artUri = first?.artUri,
            source = TrackSource.ONLINE,
            sourceKey = first?.sourceKey.orEmpty(),
        )
    }
}
