package com.szc.learning.ui.address

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.szc.learning.core.network.takeout.AddressDto
import com.szc.learning.databinding.ItemAddressBinding

/**
 * 地址列表适配器。
 *
 * @param selectMode 选择模式（从结算页进入）隐藏行内操作，点条目直接回传
 * @param selectedId 当前选中的地址，选择模式下描边标识
 */
class AddressAdapter(
    private val selectMode: Boolean,
    private val selectedId: Long,
    private val onClick: (AddressDto) -> Unit,
    private val onEdit: (AddressDto) -> Unit,
    private val onDelete: (AddressDto) -> Unit,
    private val onSetDefault: (AddressDto) -> Unit,
) : ListAdapter<AddressDto, AddressAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAddressBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemAddressBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(address: AddressDto) {
            binding.receiverText.text = address.receiver
            binding.phoneText.text = address.maskedPhone
            binding.addressText.text = address.fullAddress

            binding.tagText.isVisible = !address.tag.isNullOrBlank()
            binding.tagText.text = address.tag

            binding.defaultBadge.isVisible = address.isDefaultAddress

            binding.setDefaultButton.isVisible = !selectMode && !address.isDefaultAddress
            binding.editButton.isVisible = !selectMode
            binding.deleteButton.isVisible = !selectMode

            // 选择模式给当前选中项描边，用户才知道自己之前选的是哪条
            val selected = selectMode && address.addressId == selectedId
            binding.root.strokeWidth = if (selected) {
                (binding.root.resources.displayMetrics.density * 2).toInt()
            } else {
                0
            }
            binding.root.strokeColor = if (selected) {
                // colorPrimary 是 appcompat 的属性，material 的 R 里没有
                MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorPrimary)
            } else {
                0
            }

            binding.root.setOnClickListener {
                // 选择模式点条目回传，管理模式点条目进编辑
                if (selectMode) onClick(address) else onEdit(address)
            }
            binding.editButton.setOnClickListener { onEdit(address) }
            binding.deleteButton.setOnClickListener { onDelete(address) }
            binding.setDefaultButton.setOnClickListener { onSetDefault(address) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AddressDto>() {
            override fun areItemsTheSame(oldItem: AddressDto, newItem: AddressDto) =
                oldItem.addressId == newItem.addressId

            override fun areContentsTheSame(oldItem: AddressDto, newItem: AddressDto) =
                oldItem == newItem
        }
    }
}
