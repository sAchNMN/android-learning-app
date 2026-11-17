package com.szc.learning.ui.cart

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.data.CartLine
import com.szc.learning.databinding.ItemCartLineBinding
import com.szc.learning.ui.takeout.formatMoney

/**
 * 购物车列表。
 *
 * 行 key 是 dishId + 规格组合，所以加减回传的是 [CartLine.key] 而不是 dishId ——
 * 同一道菜选了两种规格时，用户点的是哪一行就改哪一行。
 */
class CartAdapter(
    private val onIncrease: (String) -> Unit,
    private val onDecrease: (String) -> Unit,
) : ListAdapter<CartLine, CartAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCartLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemCartLineBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(line: CartLine) {
            binding.lineName.text = line.dishName
            binding.lineSpec.isVisible = line.specText.isNotBlank()
            binding.lineSpec.text = line.specText
            binding.lineSubtotal.text = binding.root.context.getString(
                R.string.money_amount,
                formatMoney(line.subtotal),
            )
            binding.qtyText.text = line.quantity.toString()
            binding.plusButton.setOnClickListener { onIncrease(line.key) }
            binding.minusButton.setOnClickListener { onDecrease(line.key) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<CartLine>() {
            override fun areItemsTheSame(oldItem: CartLine, newItem: CartLine) =
                oldItem.key == newItem.key

            override fun areContentsTheSame(oldItem: CartLine, newItem: CartLine) =
                oldItem == newItem
        }
    }
}
