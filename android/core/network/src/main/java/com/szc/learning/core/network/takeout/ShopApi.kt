package com.szc.learning.core.network.takeout

import com.szc.learning.core.network.AjaxResult
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 店铺域接口：分类、分页列表。
 *
 * 详情仍在 TakeoutApi.shopDetail 里，等店铺详情页改造时再迁进来。
 * P2 的拆分是渐进的：不为了结构整齐而一次性重写能跑的代码。
 */
interface ShopApi {

    @GET("app/shop/category/list")
    suspend fun categories(): AjaxResult<List<ShopCategory>>

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
