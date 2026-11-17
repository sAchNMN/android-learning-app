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
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.databinding.ItemCartBinding
import com.szc.learning.databinding.SheetCartBinding

/**
 * 购物车明细底表。
 *
 * 只负责展示与回调，真正的购物车状态在 [ShopDetailViewModel] 里，
 * 所以增删都往外抛，Activity 收到新状态再回灌 [submit]。
 */
class CartSheetDialog(
    context: Context,
    private val onAdd: (Dish) -> Unit,
    private val onRemove: (Dish) -> Unit,
    private val onClear: () -> Unit,
) : BottomSheetDialog(context) {

    private val binding = SheetCartBinding.inflate(LayoutInflater.from(context))

    private val adapter = CartAdapter(
        onAdd = { onAdd(it) },
        onRemove = { onRemove(it) },
    )

    init {
        setContentView(binding.root)
        binding.cartRecycler.layoutManager = LinearLayoutManager(context)
        binding.cartRecycler.adapter = adapter
        binding.clearButton.setOnClickListener { onClear() }
    }

    fun submit(lines: List<ShopDetailViewModel.CartLine>) {
        adapter.submitList(lines)
        val empty = lines.isEmpty()
        binding.sheetEmpty.isVisible = empty
        binding.cartRecycler.isVisible = !empty
        binding.clearButton.isEnabled = !empty
        binding.sheetTitle.text = context.getString(
            R.string.cart_sheet_title,
            lines.sumOf { it.quantity },
        )
    }

    private class CartAdapter(
        private val onAdd: (Dish) -> Unit,
        private val onRemove: (Dish) -> Unit,
    ) : ListAdapter<ShopDetailViewModel.CartLine, CartAdapter.ViewHolder>(DIFF) {

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

            fun bind(line: ShopDetailViewModel.CartLine) {
                binding.cartDishName.text = line.dish.dishName
                binding.cartQty.text = line.quantity.toString()
                binding.cartSubtotal.text = binding.root.context.getString(
                    R.string.money_format,
                    formatMoney(line.subtotal),
                )
                binding.cartMinus.setOnClickListener { onRemove(line.dish) }
                binding.cartPlus.setOnClickListener { onAdd(line.dish) }
            }
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<ShopDetailViewModel.CartLine>() {
                override fun areItemsTheSame(
                    oldItem: ShopDetailViewModel.CartLine,
                    newItem: ShopDetailViewModel.CartLine,
                ) = oldItem.dish.dishId == newItem.dish.dishId

                override fun areContentsTheSame(
                    oldItem: ShopDetailViewModel.CartLine,
                    newItem: ShopDetailViewModel.CartLine,
                ) = oldItem == newItem
            }
        }
    }
}
