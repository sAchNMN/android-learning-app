package com.szc.learning.cart

import com.szc.learning.core.network.takeout.Dish
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.round

/** 购物车中的一行：菜品 + 数量。 */
data class CartItem(
    val dishId: Long,
    val dishName: String,
    val price: Double,
    val quantity: Int,
) {
    val subtotal: Double get() = round(price * quantity * 100) / 100
}

/** 购物车快照。金额统一按分取整，避免二进制误差影响起送价判断。 */
data class CartState(
    val shopId: Long = 0L,
    val shopName: String = "",
    val minAmount: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val items: List<CartItem> = emptyList(),
) {
    val itemCount: Int get() = items.sumOf { it.quantity }

    val totalAmount: Double get() = round(items.sumOf { it.subtotal } * 100) / 100

    val canCheckout: Boolean get() = itemCount > 0 && totalAmount >= minAmount

    val shortOfMin: Double get() = (minAmount - totalAmount).coerceAtLeast(0.0)

    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * 购物车，进程内单例，由 [com.szc.learning.AppContainer] 持有。
 *
 * 一次只装一家店的商品：加入别家菜时整单替换，避免跨店结算。
 * 购物车页与底部角标都观察 [state]，所以任何一处加减都会同步。
 */
class CartStore {

    private val _state = MutableStateFlow(CartState())
    val state: StateFlow<CartState> = _state.asStateFlow()

    fun add(dish: Dish, shopId: Long, shopName: String, minAmount: Double, deliveryFee: Double) {
        _state.update { current ->
            val base = if (current.shopId == shopId) {
                current
            } else {
                CartState(
                    shopId = shopId,
                    shopName = shopName,
                    minAmount = minAmount,
                    deliveryFee = deliveryFee,
                )
            }
            val items = if (base.items.any { it.dishId == dish.dishId }) {
                base.items.map {
                    if (it.dishId == dish.dishId) it.copy(quantity = it.quantity + 1) else it
                }
            } else {
                base.items + CartItem(dish.dishId, dish.dishName, dish.price, 1)
            }
            base.copy(items = items)
        }
    }

    fun increase(dishId: Long) {
        _state.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.dishId == dishId) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrease(dishId: Long) {
        _state.update { current ->
            val items = current.items.mapNotNull {
                when {
                    it.dishId != dishId -> it
                    it.quantity > 1 -> it.copy(quantity = it.quantity - 1)
                    else -> null
                }
            }
            if (items.isEmpty()) CartState() else current.copy(items = items)
        }
    }

    /** 下单成功后只清对应店铺，避免把期间新加的商品也清掉。 */
    fun clearFor(shopId: Long) {
        _state.update { current -> if (current.shopId == shopId) CartState() else current }
    }

    fun clear() {
        _state.value = CartState()
    }
}
