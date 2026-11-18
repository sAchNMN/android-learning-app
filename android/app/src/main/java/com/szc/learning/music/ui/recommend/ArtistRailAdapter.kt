package com.szc.learning.music.ui.recommend

import android.graphics.drawable.BitmapDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.databinding.ItemArtistCardBinding
import com.szc.learning.music.ui.common.CoverArt

/** 歌手横滑轨道用的轻量模型：名字 + 曲目数。 */
data class Artist(val name: String, val trackCount: Int)

class ArtistRailAdapter(
    private val onClick: (Artist) -> Unit,
) : ListAdapter<Artist, ArtistRailAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemArtistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemArtistCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(artist: Artist) {
            val context = binding.root.context
            binding.artistCover.setImageDrawable(
                BitmapDrawable(context.resources, CoverArt.of(artist.name, 280)),
            )
            binding.artistInitial.text = artist.name.take(1)
            binding.artistName.text = artist.name
            binding.root.setOnClickListener { onClick(artist) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Artist>() {
            override fun areItemsTheSame(oldItem: Artist, newItem: Artist): Boolean =
                oldItem.name == newItem.name

            override fun areContentsTheSame(oldItem: Artist, newItem: Artist): Boolean =
                oldItem == newItem
        }
    }
}