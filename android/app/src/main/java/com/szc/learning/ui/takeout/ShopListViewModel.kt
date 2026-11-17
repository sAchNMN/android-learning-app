package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.ShopCategory
import com.szc.learning.core.network.takeout.ShopRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 店铺列表状态。
 *
 * 失败拆三种，屏幕上的位置完全不同：
 * - [loadError] 首屏/切换筛选失败 → 整页错误态
 * - [moreError] 触底加载失败 → 只在底部提示，已有数据不动
 * - categories 拉失败不记录状态 → 分类栏只有「全部」，不阻塞店铺列表
 */
data class ShopListState(
    val categories: List<ShopCategory> = emptyList(),
    val selectedCategoryId: Long = 0L,
    val sort: String = ShopRepository.SORT_DEFAULT,
    val shops: List<Shop> = emptyList(),
    val pageNum: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadError: String? = null,
    val moreError: Boolean = false,
)

/**
 * 店铺列表。
 *
 * 切换分类 / 排序会取消上一个请求（[pageJob]），只认最后一次结果，
 * 否则快速连点会出现「点了 A 显示 B」。
 */
class ShopListViewModel(
    private val repository: ShopRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ShopListState())
    val state: StateFlow<ShopListState> = _state.asStateFlow()

    private var pageJob: Job? = null
    private var moreJob: Job? = null

    init {
        loadCategories()
        loadFirstPage()
    }

    fun loadCategories() {
        viewModelScope.launch {
            when (val result = repository.categories()) {
                is AppResult.Success -> _state.update { it.copy(categories = result.data) }
                // 分类拉失败不打扰用户，列表还能用
                is AppResult.Failure -> Unit
            }
        }
    }

    fun selectCategory(categoryId: Long) {
        if (_state.value.selectedCategoryId == categoryId) return
        _state.update {
            it.copy(selectedCategoryId = categoryId, shops = emptyList(), endReached = false)
        }
        loadFirstPage()
    }

    fun selectSort(sort: String) {
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort, shops = emptyList(), endReached = false) }
        loadFirstPage()
    }

    /** 下拉刷新：保留旧列表直到新数据返回，不闪空白。 */
    fun refresh() = loadFirstPage()

    fun loadFirstPage() {
        pageJob?.cancel()
        moreJob?.cancel()
        pageJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = true, loadError = null, loadingMore = false, moreError = false)
            }
            val snapshot = _state.value
            val result = repository.page(
                categoryId = snapshot.selectedCategoryId.takeIf { it > 0 },
                sort = snapshot.sort,
                pageNum = 1,
                pageSize = ShopRepository.PAGE_SIZE,
            )
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        shops = result.data.rows,
                        pageNum = 1,
                        total = result.data.total,
                        endReached = result.data.rows.size >= result.data.total,
                        loadError = null,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun loadMore() {
        val snapshot = _state.value
        if (snapshot.loading || snapshot.loadingMore || snapshot.endReached) return
        if (moreJob?.isActive == true) return

        moreJob = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, moreError = false) }
            val next = _state.value.pageNum + 1
            val result = repository.page(
                categoryId = snapshot.selectedCategoryId.takeIf { it > 0 },
                sort = snapshot.sort,
                pageNum = next,
                pageSize = ShopRepository.PAGE_SIZE,
            )
            when (result) {
                is AppResult.Success -> _state.update {
                    val merged = it.shops + result.data.rows
                    it.copy(
                        loadingMore = false,
                        shops = merged,
                        pageNum = next,
                        total = result.data.total,
                        endReached = merged.size >= result.data.total,
                        moreError = false,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loadingMore = false, moreError = true)
                }
            }
        }
    }

    companion object {
        fun factory(repository: ShopRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShopListViewModel(repository) }
        }
    }
}
