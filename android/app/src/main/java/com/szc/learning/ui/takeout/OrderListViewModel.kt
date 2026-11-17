package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Order
import com.szc.learning.core.network.takeout.TakeoutRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 订单列表状态。
 *
 * 与店铺列表同一套结构：首屏错误整页显示，触底错误只在底部提示。
 */
data class OrderListState(
    val tab: String = TakeoutRepository.TAB_ALL,
    val orders: List<Order> = emptyList(),
    val pageNum: Int = 1,
    val total: Long = 0,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val loadError: String? = null,
    val moreError: Boolean = false,
)

/**
 * 我的订单（L4）。
 *
 * 切 tab 会取消上一个请求，避免「点了已完成却显示待支付」。
 */
class OrderListViewModel(
    private val repository: TakeoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OrderListState())
    val state: StateFlow<OrderListState> = _state.asStateFlow()

    private var pageJob: Job? = null
    private var moreJob: Job? = null

    init {
        loadFirstPage()
    }

    fun selectTab(tab: String) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab, orders = emptyList(), endReached = false) }
        loadFirstPage()
    }

    fun refresh() = loadFirstPage()

    fun loadFirstPage() {
        pageJob?.cancel()
        moreJob?.cancel()
        pageJob = viewModelScope.launch {
            _state.update {
                it.copy(loading = true, loadError = null, loadingMore = false, moreError = false)
            }
            val tab = _state.value.tab
            when (val result = repository.page(tab, 1, TakeoutRepository.PAGE_SIZE)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        orders = result.data.rows,
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
            when (val result = repository.page(snapshot.tab, next, TakeoutRepository.PAGE_SIZE)) {
                is AppResult.Success -> _state.update {
                    val merged = it.orders + result.data.rows
                    it.copy(
                        loadingMore = false,
                        orders = merged,
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
        fun factory(repository: TakeoutRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { OrderListViewModel(repository) }
        }
    }
}
