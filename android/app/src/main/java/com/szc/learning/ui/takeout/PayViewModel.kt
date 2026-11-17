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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 收银台状态。
 *
 * [remainingSeconds] 只用于展示与禁用按钮，**不作为取消依据** ——
 * 本地时间可以改，超时与否由服务端 SQL 判定。
 */
data class PayState(
    val loading: Boolean = false,
    val paying: Boolean = false,
    val order: Order? = null,
    val remainingSeconds: Long = 0L,
    val loadError: String? = null,
    val actionError: String? = null,
    val paid: Boolean = false,
) {
    val expired: Boolean get() = remainingSeconds <= 0L

    val canPay: Boolean
        get() = order?.status == "0" && !expired && !paying && !paid
}

/** 收银台（D3）。只做模拟支付，不接真实渠道。 */
class PayViewModel(
    private val repository: TakeoutRepository,
    private val orderId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(PayState())
    val state: StateFlow<PayState> = _state.asStateFlow()

    private var countdownJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val result = repository.orderDetail(orderId)) {
                is AppResult.Success -> {
                    val order = result.data.order
                    _state.update { it.copy(loading = false, order = order) }
                    if (order != null) {
                        startCountdown(remainingSecondsOf(order))
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun pay() {
        if (!_state.value.canPay) return

        viewModelScope.launch {
            _state.update { it.copy(paying = true, actionError = null) }
            when (val result = repository.pay(orderId)) {
                is AppResult.Success -> {
                    countdownJob?.cancel()
                    _state.update { it.copy(paying = false, paid = true) }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(paying = false, actionError = result.message)
                }
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(actionError = null) }
    }

    /** 支付窗口是下单后 15 分钟，与服务端 SQL 的时限保持一致。 */
    private fun remainingSecondsOf(order: Order): Long {
        val created = order.createTime ?: return 0L
        return try {
            val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(created)
            val deadline = (date?.time ?: 0L) + PAY_WINDOW_MILLIS
            ((deadline - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)
        } catch (e: ParseException) {
            0L
        }
    }

    private fun startCountdown(seconds: Long) {
        countdownJob?.cancel()
        if (seconds <= 0) {
            _state.update { it.copy(remainingSeconds = 0L) }
            return
        }
        countdownJob = viewModelScope.launch {
            var left = seconds
            while (left > 0) {
                _state.update { it.copy(remainingSeconds = left) }
                delay(1000)
                left -= 1
            }
            _state.update { it.copy(remainingSeconds = 0L) }
        }
    }

    companion object {
        private const val PAY_WINDOW_MILLIS = 15 * 60 * 1000L

        fun factory(repository: TakeoutRepository, orderId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PayViewModel(repository, orderId) }
            }
    }
}
