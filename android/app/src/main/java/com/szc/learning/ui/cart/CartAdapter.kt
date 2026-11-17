package com.szc.learning.ui.cart

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.cart.CartItem
import com.szc.learning.databinding.ItemCartBinding
import com.szc.learning.ui.takeout.formatMoney

/** 购物车列表，加减按钮直接把数量变化回传给 Fragment。 */
class CartAdapter(
    private val onIncrease: (Long) -> Unit,
    private val onDecrease: (Long) -> Unit,
) : ListAdapter<CartItem, CartAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCartBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemCartBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CartItem) {
            binding.cartDishName.text = item.dishName
            binding.cartDishPrice.text = "¥${formatMoney(item.price)}"
            binding.cartQty.text = item.quantity.toString()
            binding.increaseButton.setOnClickListener { onIncrease(item.dishId) }
            binding.decreaseButton.setOnClickListener { onDecrease(item.dishId) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<CartItem>() {
            override fun areItemsTheSame(oldItem: CartItem, newItem: CartItem) =
                oldItem.dishId == newItem.dishId

            override fun areContentsTheSame(oldItem: CartItem, newItem: CartItem) =
                oldItem == newItem
        }
    }
}
