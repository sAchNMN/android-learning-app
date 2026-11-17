package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.R
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.databinding.ItemShopHeaderBinding

/** 店铺详情页顶部信息卡：只有一个 item，内容变了就整体重绘。 */
class ShopHeaderAdapter : RecyclerView.Adapter<ShopHeaderAdapter.ViewHolder>() {

    private var shop: Shop? = null

    fun submit(value: Shop?) {
        val had = shop != null
        val has = value != null
        shop = value
        when {
            !had && has -> notifyItemInserted(0)
            had && !has -> notifyItemRemoved(0)
            had && has -> notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int = if (shop == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShopHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        shop?.let { holder.bind(it) }
    }

    class ViewHolder(
        private val binding: ItemShopHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(shop: Shop) {
            val context = binding.root.context

            binding.shopName.text = shop.shopName
            binding.shopRating.text = context.getString(R.string.shop_rating, shop.ratingText)

            binding.shopDesc.isVisible = !shop.description.isNullOrBlank()
            binding.shopDesc.text = shop.description.orEmpty()

            binding.shopFee.text = listOf(
                context.getString(
                    R.string.shop_detail_fee_line,
                    formatMoney(shop.minAmount),
                    formatMoney(shop.deliveryFee),
                ),
                context.getString(R.string.shop_delivery_minutes, shop.deliveryTime),
            ).joinToString(" · ")

            binding.shopAddress.isVisible = !shop.address.isNullOrBlank()
            binding.shopAddress.text = shop.address.orEmpty()

            binding.shopPhone.isVisible = !shop.phone.isNullOrBlank()
            binding.shopPhone.text = shop.phone.orEmpty()
            // 电话那一行整行跟着隐藏，避免留个空标签
            (binding.shopPhone.parent as? View)?.isVisible = !shop.phone.isNullOrBlank()
        }
    }
}
