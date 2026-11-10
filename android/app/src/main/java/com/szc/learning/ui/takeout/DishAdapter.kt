package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.databinding.ItemDishBinding

/**
 * 菜品列表。
 *
 * 用普通 Adapter 而不是 ListAdapter：购物车数量变化时只需要整体重绘，
 * 这点数据量下 DiffUtil 的收益不值得多写的复杂度。
 */
class DishAdapter(
    private val onAdd: (Dish) -> Unit,
) : RecyclerView.Adapter<DishAdapter.ViewHolder>() {

    private val items = mutableListOf<Dish>()

    /** dishId → 数量，由 Activity 在购物车变化时灌进来。 */
    var quantities: Map<Long, Int> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submit(list: List<Dish>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDishBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], quantities[items[position].dishId] ?: 0)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemDishBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(dish: Dish, quantity: Int) {
            binding.dishName.text = dish.dishName
            binding.dishDesc.text = dish.description.orEmpty()
            binding.dishDesc.isVisible = !dish.description.isNullOrBlank()
            binding.dishPrice.text = "¥${formatMoney(dish.price)} · 已售 ${dish.sales}"

            binding.qtyText.isVisible = quantity > 0
            binding.qtyText.text = quantity.toString()

            // 同时作为仪器测试的定位依据，避免下标脆断言
            binding.addButton.contentDescription = "加入购物车：${dish.dishName}"
            binding.addButton.setOnClickListener { onAdd(dish) }
        }
    }
}
