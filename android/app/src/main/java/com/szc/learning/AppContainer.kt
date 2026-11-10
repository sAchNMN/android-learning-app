package com.szc.learning

import android.content.Context
import com.szc.learning.core.network.ApiClient
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.SessionStore

/**
 * 手动依赖容器，进程内单例。
 *
 * 依赖方向：Activity → ViewModel → Repository → ApiClient → OkHttp。
 * 新增业务模块时在这里加一个 repository 属性即可，不要在各处 new。
 */
class AppContainer(context: Context) {

    val sessionStore: SessionStore = SessionStore(context)

    private val apiClient: ApiClient = ApiClient(
        baseUrl = BuildConfig.BASE_URL,
        tokenProvider = sessionStore::token,
        debug = BuildConfig.DEBUG,
    )

    val authRepository: AuthRepository = AuthRepository(apiClient.authApi, sessionStore)
}
