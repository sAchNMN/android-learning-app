package com.szc.learning.ui.login

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.szc.learning.core.common.AppResult
import com.szc.learning.core.network.AuthRepository
import com.szc.learning.core.network.CurrentUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页状态机。
 *
 * 验证码是「后端开关」而不是「硬编码」：先请求 `/captchaImage`，
 * 看 `captchaEnabled` 再决定是否展示输入框。
 */
class LoginViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val submitting: Boolean = false,
        val errorMessage: String? = null,
        val captchaEnabled: Boolean = false,
        val captchaImage: Bitmap? = null,
        val captchaUuid: String? = null,
        val loginSucceeded: CurrentUser? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** 拉取（或刷新）验证码。后端未启动时静默降级为「不显示验证码」。 */
    fun refreshCaptcha() {
        viewModelScope.launch {
            when (val result = repository.loadCaptcha()) {
                is AppResult.Success -> {
                    val data = result.data
                    _uiState.update {
                        it.copy(
                            captchaEnabled = data.captchaEnabled,
                            captchaImage = data.img?.let(::decodeBase64Image),
                            captchaUuid = data.uuid,
                            errorMessage = null,
                        )
                    }
                }

                is AppResult.Failure -> {
                    _uiState.update { it.copy(captchaEnabled = false) }
                }
            }
        }
    }

    fun login(username: String, password: String, captcha: String) {
        if (_uiState.value.submitting) return

        viewModelScope.launch {
            val snapshot = _uiState.value
            _uiState.update { it.copy(submitting = true, errorMessage = null) }

            val result = repository.login(
                username = username,
                password = password,
                captchaCode = captcha.takeIf { snapshot.captchaEnabled }?.ifBlank { null },
                captchaUuid = snapshot.captchaUuid,
            )

            when (result) {
                is AppResult.Success -> _uiState.update {
                    it.copy(submitting = false, loginSucceeded = result.data)
                }

                is AppResult.Failure -> {
                    _uiState.update { it.copy(submitting = false, errorMessage = result.message) }
                    // 验证码一次性有效，失败后必须换一张，否则用户会拿旧码反复失败
                    if (_uiState.value.captchaEnabled) refreshCaptcha()
                }
            }
        }
    }

    fun clearError() {
        if (_uiState.value.errorMessage != null) {
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    /** 消费掉一次性事件，避免返回登录页时重复跳转。 */
    fun consumeLoginSuccess() {
        _uiState.update { it.copy(loginSucceeded = null) }
    }

    private fun decodeBase64Image(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()

    companion object {
        fun factory(repository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { LoginViewModel(repository) }
        }
    }
}
