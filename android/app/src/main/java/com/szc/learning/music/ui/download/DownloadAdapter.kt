package com.szc.learning.music.ui.download

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.TrackSource
import com.szc.learning.databinding.ItemDownloadBinding
import com.szc.learning.music.data.download.DownloadState
import com.szc.learning.music.data.download.DownloadTask
import com.szc.learning.music.data.download.OfflineTrack
import com.szc.learning.music.ui.common.bindArt

/**
 * 离线列表。
 *
 * 一行的副标题会在两种状态之间切换：
 * - 已下载：`歌手 · 8.4 MB`
 * - 正在下：`下载中 42%`（进度来自 [DownloadManager.tasks]，按 key 对齐）
 *
 * 下载中的行不显示删除按钮：删到一半的文件会让索引和磁盘对不上，
 * 要么等它下完，要么先取消队列。
 */
class DownloadAdapter(
    private val onClick: (OfflineTrack) -> Unit,
    private val onDelete: (OfflineTrack) -> Unit,
) : RecyclerView.Adapter<DownloadAdapter.Holder>() {

    private var items: List<OfflineTrack> = emptyList()
    private var tasks: Map<String, DownloadTask> = emptyMap()

    fun submit(list: List<OfflineTrack>, running: Map<String, DownloadTask>) {
        items = list
        tasks = running
        // 离线列表就几十条，整表重绘的代价远小于维护 DiffUtil + payload 的复杂度；
        // 进度变化本来就要重绘那一行，没有"只更新变化项"的收益
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: OfflineTrack) {
            binding.downloadTitle.text = entry.title
            binding.downloadCover.bindArt(artTrack(entry), sizePx = 200)
            binding.downloadSize.text = formatBytes(entry.sizeBytes)

            val task = tasks[entry.key]
            val running = task != null && (task.state == DownloadState.RUNNING || task.state == DownloadState.QUEUED)
            binding.downloadSubtitle.text = when {
                task?.state == DownloadState.RUNNING -> binding.root.context.getString(
                    R.string.download_progress,
                    task.progress.coerceAtLeast(0),
                )
                task?.state == DownloadState.QUEUED ->
                    binding.root.context.getString(R.string.download_state_queued)
                else -> listOf(entry.artist, formatBytes(entry.sizeBytes))
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
            }
            // 大小已经单独占一列，副标题里的重复只在非下载态出现一次即可
            binding.downloadSize.visibility = if (running) View.INVISIBLE else View.VISIBLE
            binding.downloadDelete.visibility = if (running) View.INVISIBLE else View.VISIBLE

            binding.root.setOnClickListener { onClick(entry) }
            binding.downloadDelete.setOnClickListener { onDelete(entry) }
        }
    }

    /** 封面只认 artUri 和 key（兜底渐变图的种子），其余字段用不着。 */
    private fun artTrack(entry: OfflineTrack) = MusicTrack(
        id = entry.id,
        title = entry.title,
        artist = entry.artist,
        album = entry.album,
        durationMs = entry.durationMs,
        uri = "",
        artUri = entry.artUri.ifBlank { null },
        source = TrackSource.ONLINE,
        sourceKey = entry.sourceKey,
    )
}

/** `12.3 MB`。离线文件动辄几十兆，KB 级别只在极小文件时出现。 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 KB"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> "%.1f MB".format(mb)
        else -> "%.0f KB".format(kb)
    }
}
