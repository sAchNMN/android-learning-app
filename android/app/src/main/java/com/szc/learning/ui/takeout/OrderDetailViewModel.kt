package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.OrderDetail
import com.szc.learning.core.network.takeout.TakeoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 订单详情状态。
 *
 * [silentReload] 用于轮询：不动 loading，失败也不弹错，
 * 用户不该因为后台轮询失败看到错误提示。
 */
data class OrderDetailState(
    val loading: Boolean = false,
    val detail: OrderDetail? = null,
    val loadError: String? = null,
    val actionError: String? = null,
    val busy: Boolean = false,
    val canceled: Boolean = false,
)

/**
 * 订单详情（D4）。
 *
 * 状态推进（超时取消、模拟接单）由服务端在查询时惰性完成，
 * 客户端只负责刷新，不自己推断状态。
 */
class OrderDetailViewModel(
    private val repository: TakeoutRepository,
    private val orderId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(OrderDetailState())
    val state: StateFlow<OrderDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _state.update { it.copy(loading = it.detail == null, loadError = null) }
            }
            when (val result = repository.orderDetail(orderId)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, detail = result.data, loadError = null)
                }

                is AppResult.Failure -> if (!silent) {
                    _state.update { it.copy(loading = false, loadError = result.message) }
                }
            }
        }
    }

    fun cancel(reason: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, actionError = null) }
            when (val result = repository.cancel(orderId, reason)) {
                is AppResult.Success -> {
                    _state.update { it.copy(busy = false, canceled = true) }
                    load(silent = true)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busy = false, actionError = result.message)
                }
            }
        }
    }

    fun confirm() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, actionError = null) }
            when (val result = repository.confirm(orderId)) {
                is AppResult.Success -> {
                    _state.update { it.copy(busy = false) }
                    load(silent = true)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(busy = false, actionError = result.message)
                }
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(actionError = null) }
    }

    companion object {
        fun factory(repository: TakeoutRepository, orderId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { OrderDetailViewModel(repository, orderId) }
            }
    }
}
