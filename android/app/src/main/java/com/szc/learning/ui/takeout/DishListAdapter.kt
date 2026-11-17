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
import com.szc.learning.databinding.ItemDishHeaderBinding

/**
 * 右侧菜品区的扁平列表项。
 *
 * 分类头与菜品放在同一个 RecyclerView 里（多类型），不用嵌套 RecyclerView：
 * 嵌套滚动会和左右联动打架，这是这类页面的经典坑。
 */
sealed interface DishItem {
    val groupIndex: Int

    data class Header(
        val category: String,
        override val groupIndex: Int,
    ) : DishItem

    data class Row(
        val dish: Dish,
        override val groupIndex: Int,
        val quantity: Int,
        val specText: String,
    ) : DishItem
}

/** 菜品列表适配器。 */
class DishListAdapter(
    private val onAdd: (Dish) -> Unit,
    private val onMinus: (Dish) -> Unit,
) : ListAdapter<DishItem, RecyclerView.ViewHolder>(DIFF) {

    /** 某个分类头在右侧列表里的位置，左侧点击时用来跳转。 */
    fun headerPositionOf(groupIndex: Int): Int =
        currentList.indexOfFirst { it is DishItem.Header && it.groupIndex == groupIndex }

    /** 某个位置属于哪个分类，右侧滚动时用来反查左侧高亮。 */
    fun groupIndexOf(position: Int): Int =
        currentList.getOrNull(position)?.groupIndex ?: -1

    override fun getItemViewType(position: Int): Int = when (currentList[position]) {
        is DishItem.Header -> TYPE_HEADER
        is DishItem.Row -> TYPE_DISH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemDishHeaderBinding.inflate(inflater, parent, false))
        } else {
            DishHolder(ItemDishBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = currentList[position]) {
            is DishItem.Header -> (holder as HeaderHolder).bind(item)
            is DishItem.Row -> (holder as DishHolder).bind(item)
        }
    }

    private class HeaderHolder(
        private val binding: ItemDishHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DishItem.Header) {
            binding.headerText.text = item.category
        }
    }

    private inner class DishHolder(
        private val binding: ItemDishBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DishItem.Row) {
            val context = binding.root.context
            val dish = item.dish

            binding.dishName.text = dish.dishName
            binding.dishDesc.isVisible = !dish.description.isNullOrBlank()
            binding.dishDesc.text = dish.description
            binding.dishSales.text = context.getString(R.string.dish_sales, dish.sales)
            binding.dishPrice.text = context.getString(R.string.money_amount, formatMoney(dish.price))

            binding.dishSpecText.isVisible = item.specText.isNotBlank()
            binding.dishSpecText.text = context.getString(R.string.dish_selected_spec, item.specText)

            val inCart = item.quantity > 0
            binding.qtyText.isVisible = inCart
            binding.minusButton.isVisible = inCart
            binding.qtyText.text = item.quantity.toString()

            binding.addButton.setOnClickListener { onAdd(dish) }
            binding.minusButton.setOnClickListener { onMinus(dish) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_DISH = 1

        val DIFF = object : DiffUtil.ItemCallback<DishItem>() {
            override fun areItemsTheSame(oldItem: DishItem, newItem: DishItem): Boolean = when {
                oldItem is DishItem.Header && newItem is DishItem.Header ->
                    oldItem.groupIndex == newItem.groupIndex

                oldItem is DishItem.Row && newItem is DishItem.Row ->
                    oldItem.dish.dishId == newItem.dish.dishId

                else -> false
            }

            override fun areContentsTheSame(oldItem: DishItem, newItem: DishItem): Boolean =
                oldItem == newItem
        }
    }
}
