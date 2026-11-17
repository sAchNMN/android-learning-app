package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.ShopRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 店铺列表排序方式。 */
enum class ShopSort { DEFAULT, RATING, DELIVERY_TIME, MIN_AMOUNT }

/**
 * 店铺列表。
 *
 * 搜索、分类、排序都在**本地**做：店铺总量只有个位数，
 * 每敲一个字就请求一次后端是给自己找抖动，也没必要给后端加接口。
 * 数据量真上来了再换成服务端分页 + 条件查询。
 */
class LegacyShopListViewModel(
    private val repository: ShopRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        /** 整页错误：列表为空时直接占满页面，而不是弹个 toast 就完事。 */
        val error: String? = null,
        /** 后端返回的全量数据，筛选只在这个集合上做，不重新请求。 */
        val shops: List<Shop> = emptyList(),
        val categories: List<String> = emptyList(),
        val keyword: String = "",
        /** null 表示「全部」。 */
        val category: String? = null,
        val sort: ShopSort = ShopSort.DEFAULT,
    ) {
        /** 是否处于筛选状态，决定空列表时提示「没数据」还是「没匹配」。 */
        val filtering: Boolean get() = keyword.isNotBlank() || category != null

        /** 真正要渲染的列表：先按分类/关键词过滤，再排序。 */
        val visible: List<Shop>
            get() {
                val matched = shops.filter { shop ->
                    (category == null || shop.category == category) && shop.matches(keyword)
                }
                return when (sort) {
                    ShopSort.DEFAULT -> matched
                    ShopSort.RATING -> matched.sortedWith(
                        compareByDescending<Shop> { it.rating }.thenBy { it.deliveryTime },
                    )

                    ShopSort.DELIVERY_TIME -> matched.sortedWith(
                        compareBy<Shop> { it.deliveryTime }.thenByDescending { it.rating },
                    )

                    ShopSort.MIN_AMOUNT -> matched.sortedWith(
                        compareBy<Shop> { it.minAmount }.thenByDescending { it.rating },
                    )
                }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.page(categoryId = null, pageSize = 100)) {
                is AppResult.Success -> {
                    val shops = result.data.rows
                    val categories = shops
                        .mapNotNull { it.category?.takeIf(String::isNotBlank) }
                        .distinct()
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            shops = shops,
                            categories = categories,
                            // 刷新后原分类可能已经不存在，回落到「全部」
                            category = current.category?.takeIf { it in categories },
                        )
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.message)
                }
            }
        }
    }

    fun setKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
    }

    fun setCategory(category: String?) {
        _state.update { it.copy(category = category) }
    }

    fun setSort(sort: ShopSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun clearFilters() {
        _state.update { it.copy(keyword = "", category = null) }
    }

    companion object {
        fun legacyFactory(repository: ShopRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LegacyShopListViewModel(repository) }
        }
    }
}

/** 关键词匹配店名 / 分类 / 简介，忽略大小写。 */
private fun Shop.matches(keyword: String): Boolean {
    val key = keyword.trim()
    if (key.isEmpty()) return true
    return listOfNotNull(shopName, category, description).any { it.contains(key, ignoreCase = true) }
}
