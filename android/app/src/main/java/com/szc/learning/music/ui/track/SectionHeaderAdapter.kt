package com.szc.learning.music.ui.track

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.databinding.ItemSectionHeaderBinding

/**
 * 搜索结果里的分区标题（在线 / 我的音乐）。
 *
 * 用 0/1 条的 adapter 接进 ConcatAdapter，而不是在 TrackAdapter 里加 view type：
 * 歌曲行和分区标题的点击语义完全不同，混在一起后 index 换算很容易出错。
 */
class SectionHeaderAdapter : RecyclerView.Adapter<SectionHeaderAdapter.Holder>() {

    private var title: CharSequence? = null

    fun setTitle(text: CharSequence?) {
        if (title == text) return
        title = text
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = if (title.isNullOrBlank()) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSectionHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.binding.sectionTitle.text = title
    }

    class Holder(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)
}