package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/** 收货地址接口。全部需要登录，用户身份由后端从 token 取。 */
interface AddressApi {

    @GET("app/address/list")
    suspend fun list(): AjaxResult<List<AddressDto>>

    @GET("app/address/{addressId}")
    suspend fun detail(@Path("addressId") addressId: Long): AjaxResult<AddressDto>

    @POST("app/address")
    suspend fun add(@Body body: AddressBody): AjaxResult<AddressIdResult>

    @PUT("app/address")
    suspend fun update(@Body body: AddressBody): AjaxResult<Any>

    @DELETE("app/address/{addressId}")
    suspend fun delete(@Path("addressId") addressId: Long): AjaxResult<Any>

    @POST("app/address/{addressId}/default")
    suspend fun setDefault(@Path("addressId") addressId: Long): AjaxResult<Any>
}
