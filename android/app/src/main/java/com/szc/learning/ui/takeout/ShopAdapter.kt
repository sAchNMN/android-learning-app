package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.databinding.ItemShopBinding

/** 店铺列表适配器。 */
class ShopAdapter(
    private val onClick: (Shop) -> Unit,
) : ListAdapter<Shop, ShopAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShopBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemShopBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(shop: Shop) {
            binding.shopName.text = shop.shopName
            binding.shopMeta.text = buildString {
                shop.category?.let { append(it).append(" · ") }
                append(shop.ratingText).append(" 分 · ")
                append(shop.deliveryTime).append(" 分钟送达")
            }
            binding.shopFee.text = "起送 ¥${formatMoney(shop.minAmount)} · 配送 ¥${formatMoney(shop.deliveryFee)}"
            binding.root.setOnClickListener { onClick(shop) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Shop>() {
            override fun areItemsTheSame(oldItem: Shop, newItem: Shop) = oldItem.shopId == newItem.shopId
            override fun areContentsTheSame(oldItem: Shop, newItem: Shop) = oldItem == newItem
        }
    }
}

/** 金额统一两位小数。 */
fun formatMoney(value: Double): String = String.format("%.2f", value)
