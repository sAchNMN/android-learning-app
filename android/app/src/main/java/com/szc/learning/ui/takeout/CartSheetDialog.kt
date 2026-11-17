package com.szc.learning.ui.takeout

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.szc.learning.R
import com.szc.learning.cart.CartItem
import com.szc.learning.databinding.ItemCartLineBinding
import com.szc.learning.databinding.SheetCartBinding

/**
 * 店铺页内的购物车明细底表。
 *
 * 数据直接来自全局 [com.szc.learning.cart.CartStore]，只负责展示与回调；
 * 增删都往外抛，宿主 Activity 调 ViewModel，再经 StateFlow 回灌 [submit]。
 */
class CartSheetDialog(
    context: Context,
    private val onIncrease: (Long) -> Unit,
    private val onDecrease: (Long) -> Unit,
    private val onClear: () -> Unit,
) : BottomSheetDialog(context) {

    private val binding = SheetCartBinding.inflate(LayoutInflater.from(context))

    private val adapter = CartLineAdapter(
        onIncrease = onIncrease,
        onDecrease = onDecrease,
    )

    init {
        setContentView(binding.root)
        binding.cartRecycler.layoutManager = LinearLayoutManager(context)
        binding.cartRecycler.adapter = adapter
        binding.clearButton.setOnClickListener { onClear() }
    }

    fun submit(items: List<CartItem>) {
        adapter.submitList(items)
        val empty = items.isEmpty()
        binding.sheetEmpty.isVisible = empty
        binding.cartRecycler.isVisible = !empty
        binding.clearButton.isEnabled = !empty
        binding.sheetTitle.text = context.getString(
            R.string.cart_sheet_title,
            items.sumOf { it.quantity },
        )
    }

    private class CartLineAdapter(
        private val onIncrease: (Long) -> Unit,
        private val onDecrease: (Long) -> Unit,
    ) : ListAdapter<CartItem, CartLineAdapter.ViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                ItemCartLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(
            private val binding: ItemCartLineBinding,
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(item: CartItem) {
                binding.cartDishName.text = item.dishName
                binding.cartQty.text = item.quantity.toString()
                binding.cartSubtotal.text = binding.root.context.getString(
                    R.string.money_format,
                    formatMoney(item.subtotal),
                )
                binding.cartMinus.setOnClickListener { onDecrease(item.dishId) }
                binding.cartPlus.setOnClickListener { onIncrease(item.dishId) }
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
}
