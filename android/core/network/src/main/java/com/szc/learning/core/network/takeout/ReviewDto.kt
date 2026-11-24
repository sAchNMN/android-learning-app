package com.szc.learning.core.network.takeout

/**
 * 提交评价的请求体。
 *
 * 只传评分维度和文字，**不传 userId / shopId** —— 服务端从订单里取，
 * 客户端没法给别人的店刷评。
 */
data class ReviewSubmitBody(
    val rating: Int,
    val tasteRating: Int? = null,
    val packRating: Int? = null,
    val deliveryRating: Int? = null,
    val tags: List<String>? = null,
    val content: String? = null,
    val anonymous: Boolean = false,
)

/** 店铺评价列表项。匿名评价的 [nickname] 由服务端替换成「匿名用户」。 */
data class ReviewRow(
    val reviewId: Long = 0,
    val nickname: String = "",
    val rating: Int = 0,
    val tags: String? = null,
    val content: String? = null,
    val reply: String? = null,
    val createTime: String = "",
)

/** 评价分页。 */
data class ReviewPage(
    val total: Long = 0,
    val rows: List<ReviewRow> = emptyList(),
)
