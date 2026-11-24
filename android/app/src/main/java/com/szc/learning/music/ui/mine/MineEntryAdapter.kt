package com.szc.learning.music.ui.mine

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.databinding.ItemMineEntryBinding

/**
 * 我的页的入口。做成数据而不是几段重复 XML，
 * 以后加"设置/字体大小"只加一行。
 */
data class MineEntry(
    val key: String,
    @DrawableRes val icon: Int,
    @StringRes val label: Int,
)

class MineEntryAdapter(
    private val entries: List<MineEntry>,
    private val onClick: (MineEntry) -> Unit,
) : RecyclerView.Adapter<MineEntryAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemMineEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(entries[position])
    }

    inner class Holder(private val binding: ItemMineEntryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: MineEntry) {
            binding.mineEntryIcon.setImageResource(entry.icon)
            binding.mineEntryLabel.setText(entry.label)
            binding.root.setOnClickListener { onClick(entry) }
        }
    }

    companion object {
        const val KEY_CLOUD = "cloud"
        const val KEY_STATS = "stats"
        const val KEY_SOURCES = "sources"
        const val KEY_IMPORT_SHARE = "import_share"

        /** 只放别处进不去的入口：本地/播放器在底栏，关于在标题栏，喜欢/最近在页面里。 */
        fun defaults(): List<MineEntry> = listOf(
            MineEntry(KEY_CLOUD, R.drawable.ic_music_note, R.string.mine_entry_cloud),
            MineEntry(KEY_SOURCES, R.drawable.ic_source, R.string.mine_entry_sources),
            MineEntry(KEY_IMPORT_SHARE, R.drawable.ic_plus, R.string.mine_entry_import_share),
            MineEntry(KEY_STATS, R.drawable.ic_chart, R.string.mine_entry_stats),
        )
    }
}