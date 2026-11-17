package com.szc.learning.ui.cart

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.cart.CartState
import com.szc.learning.cart.CartStore
import kotlinx.coroutines.flow.StateFlow

/** 购物车 tab：只做数量的增删，数据源是全局 [CartStore]。 */
class CartViewModel(
    private val cartStore: CartStore,
) : ViewModel() {

    val state: StateFlow<CartState> = cartStore.state

    fun increase(dishId: Long) = cartStore.increase(dishId)

    fun decrease(dishId: Long) = cartStore.decrease(dishId)

    companion object {
        fun factory(cartStore: CartStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { CartViewModel(cartStore) }
        }
    }
}
