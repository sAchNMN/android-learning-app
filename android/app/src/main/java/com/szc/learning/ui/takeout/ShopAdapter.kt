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
import java.util.Locale

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

            // 后端没有店铺图片，用店名首字做头像，比灰色占位块好认
            binding.shopAvatar.text = shop.shopName.take(1)
            binding.shopName.text = shop.shopName
            binding.shopRating.text = context.getString(R.string.shop_rating, shop.ratingText)

            binding.shopMeta.text = listOfNotNull(
                shop.category,
                context.getString(R.string.shop_delivery_minutes, shop.deliveryTime),
                shop.description,
            ).joinToString(" · ")

            binding.shopFee.text = context.getString(
                R.string.shop_detail_fee_line,
                formatMoney(shop.minAmount),
                formatMoney(shop.deliveryFee),
            )

            binding.shopAddress.isVisible = !shop.address.isNullOrBlank()
            binding.shopAddress.text = shop.address.orEmpty()

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

/** 金额统一两位小数。固定 Locale.CHINA，避免小数点跟着系统语言变逗号。 */
fun formatMoney(value: Double): String = String.format(Locale.CHINA, "%.2f", value)
