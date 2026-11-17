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

/** 首页聚合数据：分类 + 推荐店铺 + 热搜词。 */
data class HomeIndex(
    @SerializedName("categories") val categories: List<ShopCategory> = emptyList(),
    @SerializedName("shops") val shops: List<Shop> = emptyList(),
    @SerializedName("hotKeywords") val hotKeywords: List<String> = emptyList(),
)

/** 菜品。 */
data class Dish(
    @SerializedName("dishId") val dishId: Long = 0,
    @SerializedName("shopId") val shopId: Long = 0,
    @SerializedName("dishName") val dishName: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("price") val price: Double = 0.0,
    @SerializedName("category") val category: String? = null,
    @SerializedName("sales") val sales: Int = 0,
    /** 有规格时点「+」要弹规格窗，不能直接加车。 */
    @SerializedName("hasSpec") val hasSpec: Boolean = false,
    @SerializedName("sortNo") val sortNo: Int = 0,
    @SerializedName("specs") val specs: List<DishSpec> = emptyList(),
)

/** 规格组。required=true 表示必须选，multi=true 表示可多选。 */
data class DishSpec(
    @SerializedName("specId") val specId: Long = 0,
    @SerializedName("specName") val specName: String = "",
    @SerializedName("required") val required: Boolean = true,
    @SerializedName("multi") val multi: Boolean = false,
    @SerializedName("options") val options: List<DishSpecOption> = emptyList(),
)

/** 规格选项。priceDelta 是加价，不是最终价。 */
data class DishSpecOption(
    @SerializedName("optionId") val optionId: Long = 0,
    @SerializedName("specId") val specId: Long = 0,
    @SerializedName("optionName") val optionName: String = "",
    @SerializedName("priceDelta") val priceDelta: Double = 0.0,
    @SerializedName("isDefault") val isDefault: Boolean = false,
)

/** 店铺详情里的一个菜品分类。 */
data class DishGroup(
    @SerializedName("category") val category: String = "",
    @SerializedName("dishes") val dishes: List<Dish> = emptyList(),
)

/** 店铺详情：店铺 + 收藏状态 + 分组菜品。 */
data class ShopDetail(
    @SerializedName("shop") val shop: Shop? = null,
    @SerializedName("favorited") val favorited: Boolean = false,
    @SerializedName("groups") val groups: List<DishGroup> = emptyList(),
) {
    /** 按 dishId 找菜，购物车里只有 id 时用来取名称与基础价。 */
    fun dishOf(dishId: Long): Dish? = groups.asSequence()
        .flatMap { it.dishes.asSequence() }
        .firstOrNull { it.dishId == dishId }
}
