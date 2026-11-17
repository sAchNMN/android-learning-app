package com.szc.learning.core.network.takeout

import com.google.gson.annotations.SerializedName

/** 我的订单。 */
data class Order(
    @SerializedName("orderId") val orderId: Long = 0,
    @SerializedName("orderNo") val orderNo: String = "",
    @SerializedName("shopId") val shopId: Long = 0,
    @SerializedName("shopName") val shopName: String = "",
    @SerializedName("totalAmount") val totalAmount: Double = 0.0,
    @SerializedName("deliveryFee") val deliveryFee: Double = 0.0,
    @SerializedName("payAmount") val payAmount: Double = 0.0,
    @SerializedName("status") val status: String = "0",
    @SerializedName("receiver") val receiver: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("remark") val remark: String? = null,
    @SerializedName("payTime") val payTime: String? = null,
    @SerializedName("createTime") val createTime: String? = null,
) {
    /** 状态文案。后端用 char(1) 存，这里翻译成中文。 */
    val statusText: String
        get() = when (status) {
            "0" -> "待支付"
            "1" -> "已支付，等待接单"
            "2" -> "配送中"
            "3" -> "已完成"
            "4" -> "已取消"
            else -> "未知"
        }

    val canPay: Boolean get() = status == "0"
}

/** 订单明细。 */
data class OrderItem(
    @SerializedName("itemId") val itemId: Long = 0,
    @SerializedName("dishId") val dishId: Long = 0,
    @SerializedName("dishName") val dishName: String = "",
    @SerializedName("price") val price: Double = 0.0,
    @SerializedName("quantity") val quantity: Int = 0,
    @SerializedName("subtotal") val subtotal: Double = 0.0,
)

/** 订单详情：订单 + 明细。 */
data class OrderDetail(
    @SerializedName("order") val order: Order? = null,
    @SerializedName("items") val items: List<OrderItem> = emptyList(),
)

/** 下单请求体。 */
data class OrderCreateBody(
    @SerializedName("shopId") val shopId: Long,
    @SerializedName("items") val items: List<Item>,
    @SerializedName("receiver") val receiver: String,
    @SerializedName("phone") val phone: String,
    @SerializedName("address") val address: String,
    @SerializedName("remark") val remark: String? = null,
) {
    data class Item(
        @SerializedName("dishId") val dishId: Long,
        @SerializedName("quantity") val quantity: Int,
    )
}
