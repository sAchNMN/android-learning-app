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

    /** 首页聚合：分类 + 推荐店铺 + 热搜词。 */
    @GET("app/home/index")
    suspend fun homeIndex(): AjaxResult<HomeIndex>

    @GET("app/shop/category/list")
    suspend fun categories(): AjaxResult<List<ShopCategory>>

    /** 详情返回分组菜品 + 规格 + 收藏状态。 */
    @GET("app/shop/{shopId}")
    suspend fun detail(@Path("shopId") shopId: Long): AjaxResult<ShopDetail>

    @POST("app/favorite/{shopId}")
    suspend fun favorite(@Path("shopId") shopId: Long): AjaxResult<Any>

    @DELETE("app/favorite/{shopId}")
    suspend fun unfavorite(@Path("shopId") shopId: Long): AjaxResult<Any>

    /** 我收藏的店铺，结构同 /app/shop/page 的 rows。 */
    @GET("app/favorite/list")
    suspend fun favorites(
        @Query("pageNum") pageNum: Int,
        @Query("pageSize") pageSize: Int,
    ): AjaxResult<ShopPage>

    /** 热搜词。 */
    @GET("app/search/hot")
    suspend fun hotKeywords(@Query("limit") limit: Int): AjaxResult<List<String>>

    /** 搜索。type=shop 复用 ShopPage；type=dish 结构不同，客户端暂未接入。 */
    @GET("app/search")
    suspend fun search(
        @Query("keyword") keyword: String,
        @Query("type") type: String,
        @Query("pageNum") pageNum: Int,
        @Query("pageSize") pageSize: Int,
    ): AjaxResult<ShopPage>

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
