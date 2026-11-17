package com.szc.learning.music.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.databinding.ItemGenreBinding

/** 一个分类方块：文案 + 一种强调色。颜色只用来染玻璃，不直接铺满。 */
data class Genre(val labelRes: Int, val colorRes: Int)

class GenreAdapter(
    private val onClick: (Genre) -> Unit,
) : ListAdapter<Genre, GenreAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGenreBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemGenreBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(genre: Genre) {
            val context = binding.root.context
            binding.genreName.setText(genre.labelRes)
            // 染成分类色：玻璃填充用低透明度色，边框保持白，所以看起来是"彩色玻璃"而不是色块
            binding.genreTile.tint = ContextCompat.getColor(context, genre.colorRes)
            binding.genreTile.topAlpha = 0.34f
            binding.genreTile.bottomAlpha = 0.18f
            binding.genreTile.rimAlpha = 0.5f
            binding.genreTile.corner = context.resources.displayMetrics.density * 20f
            binding.root.setOnClickListener { onClick(genre) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Genre>() {
            override fun areItemsTheSame(oldItem: Genre, newItem: Genre): Boolean =
                oldItem.labelRes == newItem.labelRes

            override fun areContentsTheSame(oldItem: Genre, newItem: Genre): Boolean =
                oldItem == newItem
        }
    }
}

/** 八个分类，和 strings.xml 里的 genre_* 一一对应。 */
val defaultGenres: List<Genre> = listOf(
    Genre(R.string.genre_pop, R.color.accent_pink),
    Genre(R.string.genre_electronic, R.color.accent_violet),
    Genre(R.string.genre_jazz, R.color.accent_amber),
    Genre(R.string.genre_classical, R.color.accent_cyan),
    Genre(R.string.genre_rock, R.color.accent_rose),
    Genre(R.string.genre_folk, R.color.accent_amber),
    Genre(R.string.genre_hiphop, R.color.accent_violet),
    Genre(R.string.genre_ambient, R.color.accent_cyan),
)
