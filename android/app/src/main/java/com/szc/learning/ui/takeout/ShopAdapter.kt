package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
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
            val context = binding.root.context

            binding.shopName.text = shop.shopName

            // 「分类 · 4.8 分 · 月售 1286 单 · 休息中」，空项自动省略
            binding.shopMeta.text = buildList {
                shop.category?.takeIf { it.isNotBlank() }?.let(::add)
                add(context.getString(R.string.shop_rating_score, shop.ratingText))
                if (shop.monthSales > 0) {
                    add(context.getString(R.string.shop_month_sales, shop.monthSales))
                }
                if (!shop.isOpen) {
                    add(context.getString(R.string.shop_closed))
                }
            }.joinToString(" · ")

            binding.shopFee.text = context.getString(
                R.string.shop_fee_detail,
                formatMoney(shop.minAmount),
                formatMoney(shop.deliveryFee),
                shop.deliveryTime,
            )

            binding.shopOpenTime.isVisible = !shop.openTime.isNullOrBlank()
            binding.shopOpenTime.text = shop.openTime?.let {
                context.getString(R.string.shop_open_time, it)
            }

            // 休息中的店铺仍然可点进详情，但不能下单（服务端会拦）
            binding.root.alpha = if (shop.isOpen) 1f else 0.55f
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
