package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.UiState
import com.szc.learning.core.network.takeout.Order
import com.szc.learning.core.network.takeout.TakeoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 我的订单。 */
class OrderListViewModel(
    private val repository: TakeoutRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Order>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Order>>> = _state.asStateFlow()

    /** 支付结果这类一次性提示。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = UiState.Loading
            when (val result = repository.myOrders()) {
                is AppResult.Success -> _state.value = UiState.Success(result.data)
                is AppResult.Failure -> _state.value = UiState.Error(result.message, result.code)
            }
        }
    }

    fun pay(order: Order) {
        viewModelScope.launch {
            when (val result = repository.pay(order.orderId)) {
                is AppResult.Success -> {
                    _message.value = "支付成功"
                    load()
                }

                is AppResult.Failure -> _message.value = result.message
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        fun factory(repository: TakeoutRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { OrderListViewModel(repository) }
        }
    }
}
