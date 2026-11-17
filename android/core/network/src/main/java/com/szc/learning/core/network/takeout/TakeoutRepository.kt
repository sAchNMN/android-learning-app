package com.szc.learning.core.network.takeout

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.callData
import com.szc.learning.core.network.callUnit

/**
 * 外卖业务仓库。
 *
 * 和 [com.szc.learning.core.network.AuthRepository] 一样：不抛异常，
 * 统一返回 [AppResult]，让 ViewModel 只关心成功/失败。
 *
 * 调用层封装在 [callData] / [callUnit] 里，与地址仓库共用一份实现。
 */
class TakeoutRepository(
    private val api: TakeoutApi,
) {

    suspend fun shops(): AppResult<List<Shop>> = callData { api.shopList() }

    suspend fun shopDetail(shopId: Long): AppResult<ShopDetail> = callData { api.shopDetail(shopId) }

    suspend fun createOrder(body: OrderCreateBody): AppResult<OrderDetail> =
        callData { api.createOrder(body) }

    suspend fun myOrders(): AppResult<List<Order>> = callData { api.myOrders() }

    suspend fun orderDetail(orderId: Long): AppResult<OrderDetail> =
        callData { api.orderDetail(orderId) }

    /** 支付只返回 msg，没有 data，用 [callUnit]。 */
    suspend fun pay(orderId: Long): AppResult<Unit> = callUnit { api.pay(orderId) }
}
