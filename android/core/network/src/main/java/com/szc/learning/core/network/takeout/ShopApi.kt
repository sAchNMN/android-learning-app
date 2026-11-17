package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 店铺域接口：分类、分页列表、详情、收藏。
 */
interface ShopApi {

    @GET("app/shop/category/list")
    suspend fun categories(): AjaxResult<List<ShopCategory>>

    /** 详情返回分组菜品 + 规格 + 收藏状态。 */
    @GET("app/shop/{shopId}")
    suspend fun detail(@Path("shopId") shopId: Long): AjaxResult<ShopDetail>

    @POST("app/favorite/{shopId}")
    suspend fun favorite(@Path("shopId") shopId: Long): AjaxResult<Any>

    @DELETE("app/favorite/{shopId}")
    suspend fun unfavorite(@Path("shopId") shopId: Long): AjaxResult<Any>

    /**
     * @param categoryId 传 null 表示全部分类
     * @param keyword 传 null 表示不过滤（Retrofit 会省略该参数）
     * @param sort default / rating / fast / cheap
     */
    @GET("app/shop/page")
    suspend fun page(
        @Query("categoryId") categoryId: Long?,
        @Query("keyword") keyword: String?,
        @Query("sort") sort: String,
        @Query("pageNum") pageNum: Int,
        @Query("pageSize") pageSize: Int,
    ): AjaxResult<ShopPage>
}
