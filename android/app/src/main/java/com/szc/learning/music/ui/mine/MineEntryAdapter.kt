package com.szc.learning.music.ui.mine

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.databinding.ItemMineEntryBinding

/**
 * 我的页的入口。做成数据而不是 8 段重复 XML，
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
        const val KEY_FAVORITES = "favorites"
        const val KEY_RECENT = "recent"
        const val KEY_LOCAL = "local"
        const val KEY_CLOUD = "cloud"
        const val KEY_PLAYER = "player"
        const val KEY_STATS = "stats"
        const val KEY_RESCAN = "rescan"
        const val KEY_SOURCES = "sources"
        const val KEY_ABOUT = "about"

        fun defaults(): List<MineEntry> = listOf(
            MineEntry(KEY_FAVORITES, R.drawable.ic_heart_filled, R.string.mine_entry_favorites),
            MineEntry(KEY_RECENT, R.drawable.ic_clock, R.string.mine_entry_recent),
            MineEntry(KEY_LOCAL, R.drawable.ic_nav_local, R.string.mine_entry_local),
            MineEntry(KEY_CLOUD, R.drawable.ic_music_note, R.string.mine_entry_cloud),
            MineEntry(KEY_PLAYER, R.drawable.ic_nav_player, R.string.mine_entry_mv),
            MineEntry(KEY_STATS, R.drawable.ic_chart, R.string.mine_entry_stats),
            MineEntry(KEY_SOURCES, R.drawable.ic_source, R.string.mine_entry_sources),
            MineEntry(KEY_RESCAN, R.drawable.ic_refresh, R.string.mine_entry_rescan),
            MineEntry(KEY_ABOUT, R.drawable.ic_info, R.string.mine_entry_about),
        )
    }
}