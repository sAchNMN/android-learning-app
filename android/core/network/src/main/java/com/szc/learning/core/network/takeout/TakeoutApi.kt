package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * 外卖业务接口。
 *
 * 全部挂在外卖前缀下（app 开头），需要带登录 token。
 */
interface TakeoutApi {

    @GET("app/shop/list")
    suspend fun shopList(): AjaxResult<List<Shop>>

    @GET("app/shop/{shopId}")
    suspend fun shopDetail(@Path("shopId") shopId: Long): AjaxResult<ShopDetail>

    @POST("app/order/create")
    suspend fun createOrder(@Body body: OrderCreateBody): AjaxResult<OrderDetail>

    @GET("app/order/list")
    suspend fun myOrders(): AjaxResult<List<Order>>

    @GET("app/order/{orderId}")
    suspend fun orderDetail(@Path("orderId") orderId: Long): AjaxResult<OrderDetail>

    @POST("app/order/{orderId}/pay")
    suspend fun pay(@Path("orderId") orderId: Long): AjaxResult<Any>
}
