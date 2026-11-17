package com.szc.learning.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.SessionStore
import com.szc.learning.core.network.takeout.Shop
import com.szc.learning.core.network.takeout.ShopCategory
import com.szc.learning.core.network.takeout.ShopRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页状态。
 *
 * 先拿本地缓存的昵称点亮界面，再请求 `/getInfo` 校正。
 * 断网时保留缓存内容并给出提示，不把用户踢回登录页。
 *
 * 首页业务数据（分类/推荐/热搜）失败不影响会话展示，静默降级。
 */
class HomeViewModel(
    private val repository: AuthRepository,
    private val sessionStore: SessionStore,
    private val shopRepository: ShopRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val nickname: String = "",
        val account: String = "",
        val roles: List<String> = emptyList(),
        val networkError: String? = null,
        val sessionExpired: Boolean = false,
        val loggingOut: Boolean = false,
        val loggedOut: Boolean = false,
        val categories: List<ShopCategory> = emptyList(),
        val shops: List<Shop> = emptyList(),
        val hotKeywords: List<String> = emptyList(),
        val homeLoading: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        load()
        loadHome()
    }

    /** 首页聚合数据。失败只降级，不弹错 —— 用户还能点入口。 */
    fun loadHome() {
        viewModelScope.launch {
            _uiState.update { it.copy(homeLoading = true) }
            when (val result = shopRepository.homeIndex()) {
                is AppResult.Success -> _uiState.update {
                    it.copy(
                        homeLoading = false,
                        categories = result.data.categories,
                        shops = result.data.shops,
                        hotKeywords = result.data.hotKeywords,
                    )
                }

                is AppResult.Failure -> _uiState.update { it.copy(homeLoading = false) }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val cachedNickname = sessionStore.nicknameFlow.first()
            if (cachedNickname.isNotBlank()) {
                _uiState.update { it.copy(nickname = cachedNickname) }
            }

            when (val result = repository.restoreSession()) {
                is AppResult.Success -> {
                    val current = result.data
                    if (current == null) {
                        _uiState.update { it.copy(loading = false, sessionExpired = true) }
                    } else {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                nickname = current.info.displayName,
                                account = current.info.userName,
                                roles = current.roles,
                                networkError = null,
                                sessionExpired = false,
                            )
                        }
                    }
                }

                is AppResult.Failure -> _uiState.update {
                    it.copy(loading = false, networkError = result.message)
                }
            }
        }
    }

    fun logout() {
        if (_uiState.value.loggingOut) return
        viewModelScope.launch {
            _uiState.update { it.copy(loggingOut = true) }
            // 无论服务端是否成功，本地会话都会被清掉，所以直接进已登出状态
            repository.logout()
            _uiState.update { it.copy(loggingOut = false, loggedOut = true) }
        }
    }

    fun consumeLoggedOut() {
        _uiState.update { it.copy(loggedOut = false) }
    }

    companion object {
        fun factory(
            repository: AuthRepository,
            sessionStore: SessionStore,
            shopRepository: ShopRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository, sessionStore, shopRepository) }
        }
    }
}
