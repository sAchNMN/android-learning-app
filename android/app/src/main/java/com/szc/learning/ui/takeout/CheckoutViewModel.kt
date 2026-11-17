package com.szc.learning.ui.takeout

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.R
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.OrderCreateBody
import com.szc.learning.core.network.takeout.OrderDetail
import com.szc.learning.core.network.takeout.OrderPreview
import com.szc.learning.core.network.takeout.TakeoutRepository
import com.szc.learning.data.CartStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 结算页状态。
 *
 * [expectValue] 只是用户选的 value（"asap" / "18:30"），时间由服务端换算成绝对时间。
 */
data class CheckoutState(
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val preview: OrderPreview? = null,
    val expectValue: String = "asap",
    val loadError: String? = null,
    @param:StringRes val localErrorRes: Int? = null,
    val actionError: String? = null,
    val createdOrder: OrderDetail? = null,
) {
    val addressId: Long? get() = preview?.defaultAddress?.addressId?.takeIf { it > 0 }

    val expectLabel: String
        get() = preview?.expectOptions?.firstOrNull { it.value == expectValue }?.label.orEmpty()

    val canSubmit: Boolean
        get() = preview?.canSubmit == true && addressId != null && !submitting
}

/**
 * 结算页。
 *
 * 三条不变量：
 * 1. 金额全部来自 preview，客户端只展示，**不做任何加减**
 * 2. 提交时只传 items / addressId / expectTime / remark，不传金额
 * 3. clientToken 在进页面时生成一次，失败重试复用同一个，避免重复下单
 */
class CheckoutViewModel(
    private val repository: TakeoutRepository,
    private val cartStore: CartStore,
    private val shopId: Long,
) : ViewModel() {

    private val clientToken: String = UUID.randomUUID().toString()

    private val _state = MutableStateFlow(CheckoutState())
    val state: StateFlow<CheckoutState> = _state.asStateFlow()

    init {
        loadPreview()
    }

    /** [addressId] 传 null 表示沿用当前已选地址（首次为店铺默认地址）。 */
    fun loadPreview(addressId: Long? = null) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null, localErrorRes = null) }

            val items = cartItems()
            if (items.isEmpty()) {
                _state.update {
                    it.copy(loading = false, localErrorRes = R.string.checkout_empty_cart)
                }
                return@launch
            }

            val body = OrderCreateBody(
                shopId = shopId,
                items = items,
                addressId = addressId ?: _state.value.addressId,
            )
            when (val result = repository.preview(body)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, preview = result.data)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun selectExpect(value: String) {
        _state.update { it.copy(expectValue = value) }
    }

    fun submit(remark: String) {
        val snapshot = _state.value
        if (snapshot.submitting) return

        val addressId = snapshot.addressId
        if (addressId == null) {
            _state.update { it.copy(localErrorRes = R.string.checkout_need_address) }
            return
        }

        val items = cartItems()
        if (items.isEmpty()) {
            _state.update { it.copy(localErrorRes = R.string.checkout_empty_cart) }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(submitting = true, actionError = null, localErrorRes = null)
            }
            val body = OrderCreateBody(
                clientToken = clientToken,
                shopId = shopId,
                items = items,
                addressId = addressId,
                expectTime = snapshot.expectValue,
                remark = remark.trim().takeIf { it.isNotEmpty() },
            )
            when (val result = repository.createOrder(body)) {
                is AppResult.Success -> {
                    // 下单成功才清购物车，失败时用户还要回去改
                    cartStore.clear(shopId)
                    _state.update {
                        it.copy(submitting = false, createdOrder = result.data)
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(submitting = false, actionError = result.message)
                }
            }
        }
    }

    fun consumeMessages() {
        _state.update { it.copy(actionError = null, localErrorRes = null) }
    }

    private fun cartItems(): List<OrderCreateBody.Item> =
        cartStore.cartOf(shopId).lines.map { line ->
            OrderCreateBody.Item(
                dishId = line.dishId,
                quantity = line.quantity,
                optionIds = line.optionIds,
            )
        }

    companion object {
        fun factory(
            repository: TakeoutRepository,
            cartStore: CartStore,
            shopId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CheckoutViewModel(repository, cartStore, shopId) }
        }
    }
}
