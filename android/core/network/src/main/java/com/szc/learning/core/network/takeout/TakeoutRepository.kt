package com.szc.learning.core.network.takeout

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.AjaxResult
import com.szc.learning.core.network.toAppFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外卖业务仓库。
 *
 * 和 [com.szc.learning.core.network.AuthRepository] 一样：不抛异常，
 * 统一返回 [AppResult]，让 ViewModel 只关心成功/失败。
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

    suspend fun pay(orderId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.pay(orderId)
            if (response.isSuccess) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(response.msg.ifBlank { "支付失败" }, response.code)
            }
        } catch (throwable: Throwable) {
            throwable.toAppFailure()
        }
    }

    /** data 为空时视为服务端异常，避免把 null 当成功传给 UI。 */
    private suspend fun <T : Any> callData(block: suspend () -> AjaxResult<T>): AppResult<T> =
        withContext(Dispatchers.IO) {
            try {
                val response = block()
                val data = response.data
                when {
                    !response.isSuccess -> AppResult.Failure(
                        response.msg.ifBlank { "请求失败" },
                        response.code,
                    )
                    data == null -> AppResult.Failure("服务端返回数据为空", AppResult.CODE_PARSE)
                    else -> AppResult.Success(data)
                }
            } catch (throwable: Throwable) {
                throwable.toAppFailure()
            }
        }
}
