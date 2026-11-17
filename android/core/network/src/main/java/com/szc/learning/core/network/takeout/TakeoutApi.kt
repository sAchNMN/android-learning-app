package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 订单接口。
 *
 * 店铺相关的接口已迁到 [ShopApi]，这里只管订单。
 */
interface TakeoutApi {

    @POST("app/order/create")
    suspend fun createOrder(@Body body: OrderCreateBody): AjaxResult<OrderDetail>

    @GET("app/order/list")
    suspend fun myOrders(): AjaxResult<List<Order>>

    @GET("app/order/{orderId}")
    suspend fun orderDetail(@Path("orderId") orderId: Long): AjaxResult<OrderDetail>

    @POST("app/order/{orderId}/pay")
    suspend fun pay(@Path("orderId") orderId: Long): AjaxResult<Any>
}
