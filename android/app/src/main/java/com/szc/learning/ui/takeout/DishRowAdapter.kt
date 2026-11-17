package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.databinding.ItemDishBinding
import com.szc.learning.databinding.ItemDishSectionBinding

/**
 * 菜单行：要么是分类分组头，要么是一个菜品。
 *
 * 把分组和菜品塞进同一个列表，好处是滚动自然、不用维护多适配器同步；
 * 数量变化时只重绘对应那一行（DiffUtil 认的是 data class 的值）。
 */
sealed interface DishRow {

    data class Section(val title: String, val count: Int) : DishRow

    data class Item(val dish: Dish, val quantity: Int) : DishRow
}

/** 菜品列表适配器：分类分组头 + 菜品行。 */
class DishRowAdapter(
    private val onAdd: (Dish) -> Unit,
    private val onRemove: (Dish) -> Unit,
) : ListAdapter<DishRow, RecyclerView.ViewHolder>(DIFF) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is DishRow.Section -> TYPE_SECTION
        is DishRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SECTION) {
            SectionHolder(ItemDishSectionBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemDishBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is DishRow.Section -> (holder as SectionHolder).bind(row)
            is DishRow.Item -> (holder as ItemHolder).bind(row)
        }
    }

    private class SectionHolder(
        private val binding: ItemDishSectionBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: DishRow.Section) {
            val context = binding.root.context
            binding.sectionTitle.text = row.title.ifBlank {
                context.getString(R.string.dish_category_other)
            }
            binding.sectionCount.text = context.getString(R.string.shop_detail_section_count, row.count)
        }
    }

    private inner class ItemHolder(
        private val binding: ItemDishBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: DishRow.Item) {
            val dish = row.dish
            val context = binding.root.context

            binding.dishName.text = dish.dishName
            binding.dishDesc.isVisible = !dish.description.isNullOrBlank()
            binding.dishDesc.text = dish.description.orEmpty()
            binding.dishPrice.text = context.getString(
                R.string.dish_price_sales,
                formatMoney(dish.price),
                dish.sales,
            )

            val inCart = row.quantity > 0
            binding.minusButton.isVisible = inCart
            binding.qtyText.isVisible = inCart
            binding.qtyText.text = row.quantity.toString()

            // 同时作为仪器测试的定位依据，避免下标脆断言
            binding.addButton.contentDescription =
                context.getString(R.string.dish_add_desc, dish.dishName)
            binding.minusButton.contentDescription =
                context.getString(R.string.dish_remove_desc, dish.dishName)

            binding.addButton.setOnClickListener { onAdd(dish) }
            binding.minusButton.setOnClickListener { onRemove(dish) }
        }
    }

    private companion object {
        const val TYPE_SECTION = 0
        const val TYPE_ITEM = 1

        val DIFF = object : DiffUtil.ItemCallback<DishRow>() {
            override fun areItemsTheSame(oldItem: DishRow, newItem: DishRow): Boolean = when {
                oldItem is DishRow.Section && newItem is DishRow.Section -> oldItem.title == newItem.title
                oldItem is DishRow.Item && newItem is DishRow.Item -> oldItem.dish.dishId == newItem.dish.dishId
                else -> false
            }

            override fun areContentsTheSame(oldItem: DishRow, newItem: DishRow): Boolean =
                oldItem == newItem
        }
    }
}
