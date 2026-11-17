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

    suspend fun createOrder(body: OrderCreateBody): AppResult<OrderDetail> =
        callData { api.createOrder(body) }

    suspend fun myOrders(): AppResult<List<Order>> = callData { api.myOrders() }

    suspend fun orderDetail(orderId: Long): AppResult<OrderDetail> =
        callData { api.orderDetail(orderId) }

    /** 支付只返回 msg，没有 data，用 [callUnit]。 */
    suspend fun pay(orderId: Long): AppResult<Unit> = callUnit { api.pay(orderId) }
}
