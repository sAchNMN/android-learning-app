package com.szc.learning.core.network.takeout

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.callData
import com.szc.learning.core.network.callUnit

/** 店铺域仓库：分类、分页列表、详情、收藏。 */
class ShopRepository(
    private val api: ShopApi,
) {

    suspend fun categories(): AppResult<List<ShopCategory>> = callData { api.categories() }

    suspend fun detail(shopId: Long): AppResult<ShopDetail> = callData { api.detail(shopId) }

    suspend fun favorite(shopId: Long): AppResult<Unit> = callUnit { api.favorite(shopId) }

    suspend fun unfavorite(shopId: Long): AppResult<Unit> = callUnit { api.unfavorite(shopId) }

    suspend fun page(
        categoryId: Long?,
        keyword: String? = null,
        sort: String = SORT_DEFAULT,
        pageNum: Int = 1,
        pageSize: Int = PAGE_SIZE,
    ): AppResult<ShopPage> = callData {
        api.page(categoryId, keyword, sort, pageNum, pageSize)
    }

    companion object {
        const val SORT_DEFAULT = "default"
        const val SORT_RATING = "rating"
        const val SORT_FAST = "fast"
        const val SORT_CHEAP = "cheap"

        const val PAGE_SIZE = 10
    }
}
