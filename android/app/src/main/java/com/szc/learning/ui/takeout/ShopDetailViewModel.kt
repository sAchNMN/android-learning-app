package com.szc.learning.ui.takeout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.takeout.Dish
import com.szc.learning.core.network.takeout.DishSpecOption
import com.szc.learning.core.network.takeout.ShopDetail
import com.szc.learning.core.network.takeout.ShopRepository
import com.szc.learning.data.CartStore
import com.szc.learning.data.ShopCart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 店铺详情状态。
 *
 * [cart] 来自 CartStore，不在页面自己的加载流程里 ——
 * 进店时购物车可能已经有东西，不能等接口返回才显示。
 */
data class ShopDetailState(
    val loading: Boolean = false,
    val detail: ShopDetail? = null,
    val cart: ShopCart = ShopCart(),
    val favorited: Boolean = false,
    val favoriteBusy: Boolean = false,
    val loadError: String? = null,
    val actionError: String? = null,
) {
    val shopName: String get() = detail?.shop?.shopName.orEmpty()

    val minAmount: Double get() = detail?.shop?.minAmount ?: 0.0

    val deliveryFee: Double get() = detail?.shop?.deliveryFee ?: 0.0

    val shopOpen: Boolean get() = detail?.shop?.isOpen ?: false

    /** 还差多少起送，0 表示已达标。 */
    val shortOfMin: Double get() = (minAmount - cart.totalAmount).coerceAtLeast(0.0)

    val canCheckout: Boolean
        get() = shopOpen && cart.itemCount > 0 && shortOfMin <= 0.0
}

/**
 * 店铺详情 + 购物车编排。
 *
 * 购物车实体在 [CartStore]（全局单例、可按 shopId 共存），
 * ViewModel 只负责把当前店铺的那一份映射成 UI 状态。
 */
class ShopDetailViewModel(
    private val repository: ShopRepository,
    private val cartStore: CartStore,
    private val shopId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(ShopDetailState())
    val state: StateFlow<ShopDetailState> = _state.asStateFlow()

    init {
        load()
        observeCart()
    }

    private fun observeCart() {
        viewModelScope.launch {
            cartStore.carts
                .map { carts -> carts[shopId] ?: ShopCart(shopId = shopId) }
                .collect { cart -> _state.update { it.copy(cart = cart) } }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.detail == null, loadError = null) }
            when (val result = repository.detail(shopId)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        detail = result.data,
                        favorited = result.data.favorited,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, loadError = result.message)
                }
            }
        }
    }

    fun add(dish: Dish, options: List<DishSpecOption>) {
        cartStore.add(shopId, state.value.shopName, dish, options)
    }

    fun increase(lineKey: String) = cartStore.increase(shopId, lineKey)

    fun decrease(lineKey: String) = cartStore.decrease(shopId, lineKey)

    fun decreaseDish(dish: Dish) = cartStore.decreaseByDish(shopId, dish.dishId)

    fun clearCart() = cartStore.clear(shopId)

    /** 别的店还有菜时，加购前必须提示，否则结算会混单。 */
    fun hasOtherShopCart(): Boolean =
        cartStore.carts.value.any { (id, cart) -> id != shopId && !cart.isEmpty }

    fun clearOtherCarts() = cartStore.clearOthers(shopId)

    fun toggleFavorite() {
        val snapshot = _state.value
        if (snapshot.favoriteBusy || snapshot.detail == null) return

        val target = !snapshot.favorited
        viewModelScope.launch {
            _state.update { it.copy(favoriteBusy = true) }
            val result = if (target) {
                repository.favorite(shopId)
            } else {
                repository.unfavorite(shopId)
            }
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(favoriteBusy = false, favorited = target)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(favoriteBusy = false, actionError = result.message)
                }
            }
        }
    }

    fun consumeError() {
        _state.update { it.copy(actionError = null) }
    }

    companion object {
        fun factory(
            repository: ShopRepository,
            cartStore: CartStore,
            shopId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShopDetailViewModel(repository, cartStore, shopId) }
        }
    }
}
