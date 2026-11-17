package com.szc.learning

import android.content.Context
import com.szc.learning.core.network.ApiClient
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.SessionStore
import com.szc.learning.core.network.takeout.AddressRepository
import com.szc.learning.core.network.takeout.ShopRepository
import com.szc.learning.core.network.takeout.TakeoutRepository
import com.szc.learning.data.CartStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖容器，进程内单例。
 *
 * 依赖方向：Activity → ViewModel → Repository → ApiClient → OkHttp。
 * 新增业务模块时在这里加一个 repository 属性即可，不要在各处 new。
 */
class AppContainer(context: Context) {

    /** 应用级协程作用域：购物车落盘这类「不随页面销毁」的任务用它。 */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessionStore: SessionStore = SessionStore(context)

    private val apiClient: ApiClient = ApiClient(
        baseUrl = BuildConfig.BASE_URL,
        tokenProvider = sessionStore::token,
        debug = BuildConfig.DEBUG,
    )

    val authRepository: AuthRepository = AuthRepository(apiClient.authApi, sessionStore)

    val takeoutRepository: TakeoutRepository = TakeoutRepository(apiClient.takeoutApi)

    val addressRepository: AddressRepository = AddressRepository(apiClient.addressApi)

    val shopRepository: ShopRepository = ShopRepository(apiClient.shopApi)

    val cartStore: CartStore = CartStore(context, appScope)
}
