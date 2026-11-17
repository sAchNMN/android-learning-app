package com.szc.learning.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.data.CartStore
import com.szc.learning.data.ShopCart
import kotlinx.coroutines.flow.StateFlow

/**
 * 购物车 tab。
 *
 * 购物车按 shopId 分家存在 [CartStore] 里；tab 只展示「当前有货的那一家」，
 * 因为加购时已经强制清空别家，正常情况下同时只会有一家非空。
 */
class CartViewModel(
    private val cartStore: CartStore,
) : ViewModel() {

    val carts: StateFlow<Map<Long, ShopCart>> = cartStore.carts

    /** 当前要展示的购物车：取第一家有货的店。 */
    fun activeCart(carts: Map<Long, ShopCart>): ShopCart? =
        carts.values.firstOrNull { !it.isEmpty }

    fun increase(shopId: Long, lineKey: String) = cartStore.increase(shopId, lineKey)

    fun decrease(shopId: Long, lineKey: String) = cartStore.decrease(shopId, lineKey)

    companion object {
        fun factory(cartStore: CartStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { CartViewModel(cartStore) }
        }
    }
}
