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

    /** 结算预览：金额、起送差额、默认地址、配送时间档位。 */
    @POST("app/order/preview")
    suspend fun preview(@Body body: OrderCreateBody): AjaxResult<OrderPreview>

    @POST("app/order/create")
    suspend fun createOrder(@Body body: OrderCreateBody): AjaxResult<OrderDetail>

    @GET("app/order/list")
    suspend fun myOrders(): AjaxResult<List<Order>>

    @GET("app/order/{orderId}")
    suspend fun orderDetail(@Path("orderId") orderId: Long): AjaxResult<OrderDetail>

    @POST("app/order/{orderId}/pay")
    suspend fun pay(@Path("orderId") orderId: Long): AjaxResult<Any>
}
