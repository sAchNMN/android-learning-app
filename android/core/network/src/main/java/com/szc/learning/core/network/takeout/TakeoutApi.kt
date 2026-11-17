package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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

    /** @param status all / unpaid / ongoing / done */
    @GET("app/order/page")
    suspend fun page(
        @Query("status") status: String,
        @Query("pageNum") pageNum: Int,
        @Query("pageSize") pageSize: Int,
    ): AjaxResult<OrderPage>

    @GET("app/order/{orderId}")
    suspend fun orderDetail(@Path("orderId") orderId: Long): AjaxResult<OrderDetail>

    @POST("app/order/{orderId}/pay")
    suspend fun pay(@Path("orderId") orderId: Long): AjaxResult<Any>

    @POST("app/order/{orderId}/cancel")
    suspend fun cancel(
        @Path("orderId") orderId: Long,
        @Body body: OrderCancelBody,
    ): AjaxResult<Any>

    @POST("app/order/{orderId}/confirm")
    suspend fun confirm(@Path("orderId") orderId: Long): AjaxResult<Any>

    /** 提交评价：只有已完成且未评价的订单能评，一单一评。 */
    @POST("app/order/{orderId}/review")
    suspend fun review(
        @Path("orderId") orderId: Long,
        @Body body: ReviewSubmitBody,
    ): AjaxResult<Any>
}
