package com.szc.learning.core.network.takeout

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.callData
import com.szc.learning.core.network.callUnit

/**
 * 订单域仓库。
 *
 * 店铺相关的读写已迁到 [ShopRepository]。不抛异常，统一返回 [AppResult]。
 */
class TakeoutRepository(
    private val api: TakeoutApi,
) {

    suspend fun preview(body: OrderCreateBody): AppResult<OrderPreview> =
        callData { api.preview(body) }

    suspend fun createOrder(body: OrderCreateBody): AppResult<OrderDetail> =
        callData { api.createOrder(body) }

    suspend fun myOrders(): AppResult<List<Order>> = callData { api.myOrders() }

    suspend fun page(
        status: String = TAB_ALL,
        pageNum: Int = 1,
        pageSize: Int = PAGE_SIZE,
    ): AppResult<OrderPage> = callData { api.page(status, pageNum, pageSize) }

    suspend fun orderDetail(orderId: Long): AppResult<OrderDetail> =
        callData { api.orderDetail(orderId) }

    /** 支付只返回 msg，没有 data，用 [callUnit]。 */
    suspend fun pay(orderId: Long): AppResult<Unit> = callUnit { api.pay(orderId) }

    suspend fun cancel(orderId: Long, reason: String?): AppResult<Unit> =
        callUnit { api.cancel(orderId, OrderCancelBody(reason)) }

    suspend fun confirm(orderId: Long): AppResult<Unit> = callUnit { api.confirm(orderId) }

    /** 提交评价。服务端会校验订单归属、已完成状态与一单一评。 */
    suspend fun review(orderId: Long, body: ReviewSubmitBody): AppResult<Unit> =
        callUnit { api.review(orderId, body) }

    companion object {
        /** 与服务端 statusesOf() 一一对应。 */
        const val TAB_ALL = "all"
        const val TAB_UNPAID = "unpaid"
        const val TAB_ONGOING = "ongoing"
        const val TAB_DONE = "done"

        const val PAGE_SIZE = 10
    }
}
