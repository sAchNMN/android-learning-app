package com.szc.learning.ui.takeout

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.szc.learning.R
import com.szc.learning.databinding.ItemCategoryBinding

/**
 * 左侧分类栏。
 *
 * 选中态 = 背景与右侧一致 + 加粗 + 主色文字。取色走 MaterialColors，
 * 深浅色模式都不用另写一套。
 */
class CategoryAdapter(
    private val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private val items = mutableListOf<String>()

    var selectedIndex: Int = 0
        private set

    fun submit(categories: List<String>, selected: Int = 0) {
        items.clear()
        items.addAll(categories)
        selectedIndex = selected.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        notifyDataSetChanged()
    }

    fun select(index: Int) {
        if (index == selectedIndex || index !in items.indices) return
        val previous = selectedIndex
        selectedIndex = index
        notifyItemChanged(previous)
        notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position == selectedIndex)
    }

    inner class ViewHolder(
        private val binding: ItemCategoryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private val primaryColor = MaterialColors.getColor(
            binding.root,
            androidx.appcompat.R.attr.colorPrimary,
        )

        private val defaultTextColors = binding.categoryText.textColors

        fun bind(category: String, selected: Boolean) {
            binding.categoryText.text = category
            binding.categoryText.isSelected = selected
            binding.categoryText.alpha = if (selected) 1f else 0.65f
            binding.categoryText.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            // setTextColor 有 int 与 ColorStateList 两个重载，if 两侧类型必须一致
            binding.categoryText.setTextColor(
                if (selected) primaryColor else defaultTextColors.defaultColor,
            )
            binding.categoryText.setBackgroundResource(
                if (selected) R.drawable.bg_category_selected else 0,
            )
            binding.categoryText.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(position)
            }
        }
    }
}
