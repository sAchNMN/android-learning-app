package com.szc.learning.core.network.takeout

import com.google.gson.annotations.SerializedName

/** 店铺。金额用 Double，后端是 BigDecimal，Gson 直接转。 */
data class Shop(
    @SerializedName("shopId") val shopId: Long = 0,
    @SerializedName("shopName") val shopName: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("image") val image: String? = null,
    @SerializedName("category") val category: String? = null,
    @SerializedName("categoryId") val categoryId: Long = 0,
    @SerializedName("address") val address: String? = null,
    @SerializedName("phone") val phone: String? = null,
    @SerializedName("deliveryFee") val deliveryFee: Double = 0.0,
    @SerializedName("minAmount") val minAmount: Double = 0.0,
    @SerializedName("deliveryTime") val deliveryTime: Int = 0,
    @SerializedName("openTime") val openTime: String? = null,
    @SerializedName("rating") val rating: Double = 0.0,
    @SerializedName("monthSales") val monthSales: Int = 0,
    @SerializedName("status") val status: String = "0",
) {
    /** 评分展示成一位小数。 */
    val ratingText: String get() = String.format("%.1f", rating)

    /** 状态 0 营业中 / 1 休息。休息中仍可进详情，下单由服务端拦。 */
    val isOpen: Boolean get() = status == "0"
}

/** 店铺分类。 */
data class ShopCategory(
    @SerializedName("categoryId") val categoryId: Long = 0,
    @SerializedName("categoryName") val categoryName: String = "",
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("sortNo") val sortNo: Int = 0,
)

/** 分页响应：{ total, rows }。 */
data class ShopPage(
    @SerializedName("total") val total: Long = 0,
    @SerializedName("rows") val rows: List<Shop> = emptyList(),
)
