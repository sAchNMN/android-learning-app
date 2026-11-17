package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.cart.CartItem
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
 * 购物车数据不归本 ViewModel 独占，而是写入全局 [CartStore]：
 * 退出店铺页后购物车 tab 与底部角标读到的是同一份。
 * 这里只把「本店那一份」映射进 UI 状态，别家的商品不显示。
 */
class ShopDetailViewModel(
    private val repository: TakeoutRepository,
    private val cartStore: CartStore,
    private val shopId: Long,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val detail: ShopDetail? = null,
        /** dishId → 数量，喂给菜单行做加减态。 */
        val cart: Map<Long, Int> = emptyMap(),
        /** 购物车明细，喂给底表。 */
        val cartItems: List<CartItem> = emptyList(),
        val totalAmount: Double = 0.0,
        val submitting: Boolean = false,
        /** 整页错误：详情没拉到时占满页面并给重试按钮。 */
        val loadError: String? = null,
        /** 一次性错误：下单失败之类，toast 一下就清掉。 */
        val actionError: String? = null,
        val createdOrder: Order? = null,
    ) {
        val itemCount: Int get() = cart.values.sum()

        val minAmount: Double get() = detail?.shop?.minAmount ?: 0.0

        /** 达到起送价才能结算。 */
        val canCheckout: Boolean
            get() = itemCount > 0 && totalAmount >= minAmount

        val shortOfMin: Double
            get() = (minAmount - totalAmount).coerceAtLeast(0.0)

        /** 菜单行：按菜品分类分组，分组头带上数量。 */
        val rows: List<DishRow>
            get() {
                val dishes = detail?.dishes.orEmpty()
                if (dishes.isEmpty()) return emptyList()
                return buildList {
                    dishes.groupBy { it.category.orEmpty() }.forEach { (category, group) ->
                        add(DishRow.Section(category, group.size))
                        group.forEach { add(DishRow.Item(it, cart[it.dishId] ?: 0)) }
                    }
                }
            }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            cartStore.state.collect { cart ->
                val mine = cart.takeIf { it.shopId == shopId }
                _state.update { current ->
                    current.copy(
                        cart = mine?.items?.associate { it.dishId to it.quantity }.orEmpty(),
                        cartItems = mine?.items.orEmpty(),
                        totalAmount = mine?.totalAmount ?: 0.0,
                    )
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val result = repository.shopDetail(shopId)) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, detail = result.data)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun addDish(dish: Dish) {
        val shop = _state.value.detail?.shop ?: return
        cartStore.add(dish, shop.shopId, shop.shopName, shop.minAmount, shop.deliveryFee)
    }

    fun increaseDish(dishId: Long) {
        cartStore.increase(dishId)
    }

    fun removeDish(dish: Dish) {
        cartStore.decrease(dish.dishId)
    }

    fun removeDish(dishId: Long) {
        cartStore.decrease(dishId)
    }

    fun clearCart() {
        cartStore.clear()
    }

    fun checkout(receiver: String, phone: String, address: String) {
        val snapshot = _state.value
        if (snapshot.itemCount == 0 || snapshot.submitting) return

        val items = snapshot.cart.map { (dishId, qty) -> OrderCreateBody.Item(dishId, qty) }

        viewModelScope.launch {
            _state.update { it.copy(submitting = true, actionError = null) }
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
                    )

                    is AppResult.Failure -> current.copy(
                        submitting = false,
                        actionError = result.message,
                    )
                }
            }
        }
    }

    fun consumeCreatedOrder() {
        _state.update { it.copy(createdOrder = null) }
    }

    fun clearActionError() {
        _state.update { it.copy(actionError = null) }
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
