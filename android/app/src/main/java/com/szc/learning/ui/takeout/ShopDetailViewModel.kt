package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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
 * 购物车只存在内存里（Map<dishId, qty>），退出页面即清空 ——
 * 演示项目不做购物车持久化，够用且不会出现「昨天的菜今天还在」的尴尬。
 */
class ShopDetailViewModel(
    private val repository: TakeoutRepository,
    private val shopId: Long,
) : ViewModel() {

    /** 购物车里的一行：菜品 + 数量 + 小计。 */
    data class CartLine(val dish: Dish, val quantity: Int) {
        val subtotal: Double get() = dish.price * quantity
    }

    data class UiState(
        val loading: Boolean = true,
        val detail: ShopDetail? = null,
        val cart: Map<Long, Int> = emptyMap(),
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

        /** 购物车明细，顺序跟随菜单顺序，不会因为点菜顺序乱跳。 */
        val lines: List<CartLine>
            get() {
                val dishes = detail?.dishes.orEmpty().associateBy { it.dishId }
                return cart.mapNotNull { (dishId, quantity) ->
                    dishes[dishId]?.let { CartLine(it, quantity) }
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
        _state.update { current ->
            val cart = current.cart.toMutableMap()
            cart[dish.dishId] = (cart[dish.dishId] ?: 0) + 1
            current.copy(cart = cart, totalAmount = totalOf(cart, current.detail))
        }
    }

    fun removeDish(dish: Dish) {
        removeDish(dish.dishId)
    }

    fun removeDish(dishId: Long) {
        _state.update { current ->
            val cart = current.cart.toMutableMap()
            val next = (cart[dishId] ?: 0) - 1
            if (next <= 0) cart.remove(dishId) else cart[dishId] = next
            current.copy(cart = cart, totalAmount = totalOf(cart, current.detail))
        }
    }

    fun clearCart() {
        _state.update { it.copy(cart = emptyMap(), totalAmount = 0.0) }
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

    private fun totalOf(cart: Map<Long, Int>, detail: ShopDetail?): Double {
        val prices = detail?.dishes.orEmpty().associate { it.dishId to it.price }
        return cart.entries.sumOf { (dishId, qty) -> (prices[dishId] ?: 0.0) * qty }
    }

    companion object {
        fun factory(repository: TakeoutRepository, shopId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ShopDetailViewModel(repository, shopId) }
            }
    }
}
