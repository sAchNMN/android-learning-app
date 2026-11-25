package com.szc.learning.music.ui.common

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.common.music.MusicTrack
import com.szc.learning.core.common.music.formatDuration
import com.szc.learning.databinding.ItemQueueTrackBinding
import com.szc.learning.ui.theme.themeColor
import java.util.concurrent.Executors

/**
 * 播放队列的行。与 [com.szc.learning.music.ui.track.TrackAdapter] 分开是因为
 * 队列要「改」列表（拖动、删除），那个只负责渲染 + 抛点击位置，用在搜索结果和
 * 本地歌曲页；给它塞编辑能力，那些页面就得多一堆用不上的状态。
 *
 * 本地持有一份可变列表，编辑先应用到它再回调给页面：
 * ItemTouchHelper 要求 `onMove` 同步返回结果，走 ViewModel 的异步回执来不及。
 * 页面负责把同一份改动落到播放器和 feed。
 */
class QueueAdapter(
    private val onPick: (index: Int) -> Unit,
    private val onMove: (from: Int, to: Int) -> Boolean,
    private val onRemove: (index: Int) -> Boolean,
    private val onCountChanged: (count: Int) -> Unit,
) : RecyclerView.Adapter<QueueAdapter.Holder>() {

    private val items = mutableListOf<MusicTrack>()

    private var activeKey: String? = null

    /** 拖动中收到的队列更新一律丢掉：边拖边重建列表会让被拖的行弹回原位。 */
    var dragging = false

    /**
     * 正在执行一次编辑（拖动/删除）。
     *
     * 编辑过程中 ViewModel 会把新队列回灌进来，而那时本地列表还没改，
     * 重建会让"删除"变成整表刷新、还会在 RecyclerView 计算布局时抛
     * `Cannot call this method while RecyclerView is computing a layout`。
     * 这段时间的更新直接丢弃，编辑完成后本地列表就是最新的。
     */
    private var editing = false

    /** 后台算 diff 用；generation 用来丢弃过期的对比结果。 */
    private val diffExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var diffGeneration = 0

    fun submit(list: List<MusicTrack>) {
        if (dragging || editing) return
        if (list.map { it.key } == items.map { it.key }) return
        // 整队对比放到后台：千首队列每次追加都算一遍两两比对会卡住拖动的帧
        val generation = ++diffGeneration
        val old = items.toList()
        diffExecutor.execute {
            val result = DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize(): Int = old.size

                    override fun getNewListSize(): Int = list.size

                    override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                        old[oldPos].key == list[newPos].key

                    override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                        old[oldPos] == list[newPos]
                },
                /* detectMoves = */ false,
            )
            mainHandler.post {
                // 期间又来了新列表 / 开始拖动 / 正在编辑：这次结果作废
                if (generation != diffGeneration || dragging || editing) return@post
                items.clear()
                items.addAll(list)
                result.dispatchUpdatesTo(this)
                onCountChanged(items.size)
            }
        }
    }

    /** 正在播的那首换人了：只刷新两行，不重建整表。 */
    fun setActive(key: String?) {
        if (key == activeKey) return
        val oldKey = activeKey
        activeKey = key
        if (oldKey != null) notifyKeyChanged(oldKey)
        if (key != null) notifyKeyChanged(key)
    }

    /** ItemTouchHelper.onMove 的落点：先问页面能不能动，再动本地列表。 */
    fun moveItem(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices || from == to) return false
        return withEdit {
            if (!onMove(from, to)) return@withEdit false
            diffGeneration++ // 作废还在后台算的整表 diff
            items.add(to, items.removeAt(from))
            notifyItemMoved(from, to)
            true
        }
    }

    /** 左滑删除。页面拒绝（删的是正在播的那首）时把行还原，返回 false。 */
    fun removeAt(position: Int): Boolean {
        if (position !in items.indices) return false
        return withEdit {
            if (!onRemove(position)) {
                notifyItemChanged(position)
                return@withEdit false
            }
            diffGeneration++ // 同上：本地已经改过，过期的 diff 不能再覆盖
            items.removeAt(position)
            notifyItemRemoved(position)
            onCountChanged(items.size)
            true
        }
    }

    /** 编辑期间屏蔽队列回灌，见 [editing]。 */
    private inline fun <T> withEdit(block: () -> T): T {
        editing = true
        try {
            return block()
        } finally {
            editing = false
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemQueueTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position], items[position].key == activeKey)
    }

    private fun notifyKeyChanged(key: String) {
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0) notifyItemChanged(index)
    }

    inner class Holder(private val binding: ItemQueueTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: MusicTrack, active: Boolean) {
            val context = binding.root.context
            binding.queueTitle.text = track.title
            binding.queueTitle.setTextColor(
                context.themeColor(if (active) R.attr.auAccent else R.attr.auInk),
            )
            binding.queueArtist.text = track.artist.ifBlank {
                context.getString(R.string.player_unknown_artist)
            }
            binding.queueDuration.text =
                if (track.durationMs > 0) formatDuration(track.durationMs) else ""
            binding.queueDuration.isVisible = track.durationMs > 0
            binding.queueCover.bindArt(track, sizePx = 200)
            binding.root.setOnClickListener {
                // 用 bindingAdapterPosition：拖动/删除后旧的 position 会点错歌
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onPick(index)
            }
        }
    }
}
