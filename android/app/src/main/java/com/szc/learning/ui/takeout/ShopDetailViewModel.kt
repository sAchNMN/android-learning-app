package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.cart.CartStore
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.core.network.takeout.Order
import com.szc.learning.core.network.takeout.OrderCreateBody
import com.szc.learning.core.network.takeout.ShopDetail
import com.szc.learning.core.network.takeout.TakeoutRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 店铺详情 + 购物车。
 *
 * 购物车数据不再由本 ViewModel 独占，而是写入全局 [CartStore]：
 * 这样退出店铺页后购物车 tab 和底部角标还能看到同样的内容。
 */
class ShopDetailViewModel(
    private val repository: TakeoutRepository,
    private val cartStore: CartStore,
    private val shopId: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ShopDetail? = null,
        val cart: Map<Long, Int> = emptyMap(),
        val totalAmount: Double = 0.0,
        val submitting: Boolean = false,
        val errorMessage: String? = null,
        val createdOrder: Order? = null,
    ) {
        val itemCount: Int get() = cart.values.sum()

        /** 达到起送价才能结算。 */
        val canCheckout: Boolean
            get() {
                val min = detail?.shop?.minAmount ?: 0.0
                return itemCount > 0 && totalAmount >= min
            }

        val shortOfMin: Double
            get() = ((detail?.shop?.minAmount ?: 0.0) - totalAmount).coerceAtLeast(0.0)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
        // 购物车是全局的：只取本店那一份，别家店的内容不显示
        viewModelScope.launch {
            cartStore.state.collect { cart ->
                val mine = cart.takeIf { it.shopId == shopId }
                _state.update { current ->
                    current.copy(
                        cart = mine?.items?.associate { it.dishId to it.quantity }.orEmpty(),
                        totalAmount = mine?.totalAmount ?: 0.0,
                    )
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, errorMessage = null) }
            when (val result = repository.shopDetail(shopId)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, detail = result.data)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, errorMessage = result.message)
                }
            }
        }
    }

    fun addDish(dish: Dish) {
        val shop = _state.value.detail?.shop ?: return
        cartStore.add(dish, shop.shopId, shop.shopName, shop.minAmount, shop.deliveryFee)
    }

    fun removeDish(dishId: Long) {
        cartStore.decrease(dishId)
    }

    fun checkout(receiver: String, phone: String, address: String) {
        val snapshot = _state.value
        if (snapshot.itemCount == 0 || snapshot.submitting) return

        val items = snapshot.cart.map { (dishId, qty) -> OrderCreateBody.Item(dishId, qty) }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, errorMessage = null) }
            val result = repository.createOrder(
                OrderCreateBody(
                    shopId = shopId,
                    items = items,
                    receiver = receiver,
                    phone = phone,
                    address = address,
                ),
            )
            if (result is AppResult.Success) {
                cartStore.clearFor(shopId)
            }
            _state.update { current ->
                when (result) {
                    is AppResult.Success -> current.copy(
                        submitting = false,
                        createdOrder = result.data.order,
                        cart = emptyMap(),
                        totalAmount = 0.0,
                    )

                    is AppResult.Failure -> current.copy(
                        submitting = false,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun consumeCreatedOrder() {
        _state.update { it.copy(createdOrder = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    companion object {
        fun factory(
            repository: TakeoutRepository,
            cartStore: CartStore,
            shopId: Long,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ShopDetailViewModel(repository, cartStore, shopId) }
            }
    }
}
