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
    @SerializedName("itemCount") val itemCount: Int = 0,
    @SerializedName("status") val status: String = "0",
    @SerializedName("receiver") val receiver: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("address") val address: String? = null,
    @SerializedName("remark") val remark: String? = null,
    @SerializedName("expectTime") val expectTime: String? = null,
    @SerializedName("payTime") val payTime: String? = null,
    @SerializedName("deliverTime") val deliverTime: String? = null,
    @SerializedName("receiveTime") val receiveTime: String? = null,
    @SerializedName("finishTime") val finishTime: String? = null,
    @SerializedName("cancelReason") val cancelReason: String? = null,
    @SerializedName("cancelTime") val cancelTime: String? = null,
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
    @SerializedName("specText") val specText: String? = null,
    @SerializedName("optionIds") val optionIds: String? = null,
    @SerializedName("price") val price: Double = 0.0,
    @SerializedName("quantity") val quantity: Int = 0,
    @SerializedName("subtotal") val subtotal: Double = 0.0,
)

/** 订单详情：订单 + 明细 + 评价状态 + 时间轴。 */
data class OrderDetail(
    @SerializedName("order") val order: Order? = null,
    @SerializedName("items") val items: List<OrderItem> = emptyList(),
    @SerializedName("reviewed") val reviewed: Boolean = false,
    @SerializedName("timeline") val timeline: List<TimelineNode> = emptyList(),
)

/** 订单分页响应：{ total, rows }。 */
data class OrderPage(
    @SerializedName("total") val total: Long = 0,
    @SerializedName("rows") val rows: List<Order> = emptyList(),
)

/** 时间轴节点。只包含已发生的节点，最后一项即当前状态。 */
data class TimelineNode(
    @SerializedName("status") val status: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("time") val time: String = "",
)

/** 取消订单请求体。 */
data class OrderCancelBody(
    @SerializedName("reason") val reason: String? = null,
)

/** 结算预览里的一行，单价已含规格加价。 */
data class PreviewItem(
    @SerializedName("dishId") val dishId: Long = 0,
    @SerializedName("dishName") val dishName: String = "",
    @SerializedName("specText") val specText: String? = null,
    @SerializedName("price") val price: Double = 0.0,
    @SerializedName("quantity") val quantity: Int = 0,
    @SerializedName("subtotal") val subtotal: Double = 0.0,
)

/** 配送时间档位。value 回传给 create 的 expectTime。 */
data class ExpectOption(
    @SerializedName("value") val value: String = "",
    @SerializedName("label") val label: String = "",
    @SerializedName("expectTime") val expectTime: String = "",
)

/**
 * 结算预览。
 *
 * 金额全部来自服务端，客户端只展示，不做任何加减乘除。
 */
data class OrderPreview(
    @SerializedName("shopId") val shopId: Long = 0,
    @SerializedName("shopName") val shopName: String = "",
    @SerializedName("items") val items: List<PreviewItem> = emptyList(),
    @SerializedName("totalAmount") val totalAmount: Double = 0.0,
    @SerializedName("deliveryFee") val deliveryFee: Double = 0.0,
    @SerializedName("payAmount") val payAmount: Double = 0.0,
    @SerializedName("minAmount") val minAmount: Double = 0.0,
    @SerializedName("shortOfMin") val shortOfMin: Double = 0.0,
    @SerializedName("defaultAddress") val defaultAddress: AddressDto? = null,
    @SerializedName("expectOptions") val expectOptions: List<ExpectOption> = emptyList(),
) {
    val canSubmit: Boolean get() = shortOfMin <= 0.0 && items.isNotEmpty()
}

/**
 * 下单 / 预览请求体。
 *
 * **不含任何金额字段**：价格由服务端按菜品与规格重算，客户端改了也没用。
 */
data class OrderCreateBody(
    @SerializedName("clientToken") val clientToken: String? = null,
    @SerializedName("shopId") val shopId: Long,
    @SerializedName("items") val items: List<Item>,
    @SerializedName("addressId") val addressId: Long? = null,
    @SerializedName("expectTime") val expectTime: String? = null,
    @SerializedName("remark") val remark: String? = null,
) {
    data class Item(
        @SerializedName("dishId") val dishId: Long,
        @SerializedName("quantity") val quantity: Int,
        @SerializedName("optionIds") val optionIds: List<Long> = emptyList(),
    )
}
