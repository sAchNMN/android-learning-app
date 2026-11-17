package com.szc.learning.ui.takeout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.szc.learning.core.network.takeout.Order
import com.szc.learning.databinding.ItemOrderBinding

/** 订单列表适配器。点卡片进详情，待支付的另有「去支付」直达收银台。 */
class OrderAdapter(
    private val onClick: (Order) -> Unit,
    private val onPay: (Order) -> Unit,
) : ListAdapter<Order, OrderAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemOrderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(order: Order) {
            binding.orderShop.text = order.shopName
            binding.orderStatus.text = order.statusText
            binding.orderItems.text = listOfNotNull(
                order.receiver?.takeIf { it.isNotBlank() },
                order.address?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "—" }
            binding.orderMeta.text = "${order.orderNo} · ${order.createTime.orEmpty()}"
            binding.orderAmount.text = "实付 ¥${formatMoney(order.payAmount)}"

            binding.payButton.isVisible = order.canPay
            binding.payButton.setOnClickListener { onPay(order) }
            binding.root.setOnClickListener { onClick(order) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Order>() {
            override fun areItemsTheSame(oldItem: Order, newItem: Order) = oldItem.orderId == newItem.orderId
            override fun areContentsTheSame(oldItem: Order, newItem: Order) = oldItem == newItem
        }
    }
}
