package com.szc.learning.music.ui.source

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.network.music.MusicSourceInfo
import com.szc.learning.databinding.ItemSourceBinding

/**
 * 音源管理列表。
 *
 * 只抛事件，不改数据：开关、排序、Cookie 最终都要走服务端，
 * 本地先改会让「保存失败」时 UI 和库里不一致。
 */
class SourceAdapter(
    private val onToggle: (MusicSourceInfo, Boolean) -> Unit,
    private val onMove: (MusicSourceInfo, Int) -> Unit,
    private val onCookie: (MusicSourceInfo) -> Unit,
) : ListAdapter<MusicSourceInfo, SourceAdapter.Holder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemSourceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class Holder(private val binding: ItemSourceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MusicSourceInfo, position: Int) {
            val context = binding.root.context
            binding.sourceName.text = item.label
            val state = context.getString(
                if (item.enabled) R.string.source_enabled else R.string.source_disabled,
            )
            binding.sourceStatus.text =
                "$state · ${context.getString(R.string.source_priority, item.priority)}"

            binding.sourceSwitch.setOnCheckedChangeListener(null)
            binding.sourceSwitch.isChecked = item.enabled
            binding.sourceSwitch.setOnCheckedChangeListener { _, checked ->
                onToggle(item, checked)
            }

            val cookieState =
                context.getString(if (item.hasCookie) R.string.source_cookie_set else R.string.source_cookie_unset)
            binding.sourceCookie.text = "${context.getString(R.string.source_cookie)} · $cookieState"
            binding.sourceCookie.setOnClickListener { onCookie(item) }

            // 第一项不能上移、最后一项不能下移；置灰而不是隐藏，避免卡片高度跳动
            binding.sourceUp.isEnabled = position > 0
            binding.sourceUp.alpha = if (position > 0) 1f else 0.35f
            binding.sourceUp.setOnClickListener { if (position > 0) onMove(item, -1) }

            binding.sourceDown.isEnabled = position < itemCount - 1
            binding.sourceDown.alpha = if (position < itemCount - 1) 1f else 0.35f
            binding.sourceDown.setOnClickListener {
                if (position < itemCount - 1) onMove(item, 1)
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<MusicSourceInfo>() {
            override fun areItemsTheSame(oldItem: MusicSourceInfo, newItem: MusicSourceInfo): Boolean =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: MusicSourceInfo, newItem: MusicSourceInfo): Boolean =
                oldItem == newItem
        }
    }
}