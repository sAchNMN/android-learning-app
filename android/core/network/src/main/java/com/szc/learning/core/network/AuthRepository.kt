package com.szc.learning.core.network

import com.szc.learning.core.common.AppResult
import com.szc.learning.core.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 认证仓库：登录、恢复会话、登出、验证码。
 *
 * 所有方法都返回 [AppResult]，不向外抛异常，ViewModel 只负责展示。
 */
class AuthRepository(
    private val api: AuthApi,
    private val sessionStore: SessionStore,
) {

    /**
     * 账号密码登录。
     *
     * 分两步：先换 token，再用 token 拉 `/getInfo`。
     * 少这一步的话登录成功后拿不到昵称和角色，首页只能显示空白。
     */
    suspend fun login(
        username: String,
        password: String,
        captchaCode: String? = null,
        captchaUuid: String? = null,
    ): AppResult<CurrentUser> = withContext(Dispatchers.IO) {
        try {
            val response = api.login(LoginRequest(username, password, captchaCode, captchaUuid))

            if (!response.isSuccess) {
                return@withContext AppResult.Failure(
                    response.msg.ifBlank { "登录失败" },
                    response.code,
                )
            }

            val token = response.token
            if (token.isNullOrBlank()) {
                return@withContext AppResult.Failure(
                    "登录响应里没有 token，请检查后端地址与接口前缀",
                    AppResult.CODE_PARSE,
                )
            }

            // 先落 token：下一步 getInfo 要靠拦截器带上认证头
            sessionStore.save(token, null, response.expiresIn)

            val info = api.getInfo()
            val user = info.user
            if (!info.isSuccess || user == null) {
                sessionStore.clear()
                return@withContext AppResult.Failure(
                    info.msg.ifBlank { "获取用户信息失败" },
                    info.code,
                )
            }

            sessionStore.updateUser(user)
            AppResult.Success(CurrentUser(user, info.roles, info.permissions))
        } catch (throwable: Throwable) {
            Logger.w("Auth", "登录失败", throwable)
            throwable.toAppFailure()
        }
    }

    /**
     * 冷启动恢复会话。
     *
     * 返回 `Success(null)` 表示「本地没有有效会话」，属于正常流程；
     * 只有后端明确返回 401 才清 token。网络异常或服务端 5xx 时返回 `Failure`
     * 且**不清 token**，避免用户一断网或后端抖动就被登出。
     */
    suspend fun restoreSession(): AppResult<CurrentUser?> = withContext(Dispatchers.IO) {
        val token = sessionStore.restore()
        if (token.isNullOrBlank()) {
            return@withContext AppResult.Success(null)
        }

        try {
            val info = api.getInfo()
            val user = info.user
            when {
                info.isSuccess && user != null -> {
                    sessionStore.updateUser(user)
                    AppResult.Success(CurrentUser(user, info.roles, info.permissions))
                }

                // token 过期或被踢下线，清掉本地会话
                info.code == CODE_UNAUTHORIZED -> {
                    sessionStore.clear()
                    AppResult.Success(null)
                }

                // 服务端异常等非认证失败：保留会话，首页继续展示缓存的用户信息
                else -> AppResult.Failure(
                    info.msg.ifBlank { "获取用户信息失败" },
                    info.code,
                )
            }
        } catch (throwable: Throwable) {
            throwable.toAppFailure()
        }
    }

    /**
     * 登出。
     *
     * 即便服务端调用失败也清本地会话：用户点「退出」就该立刻退出，
     * 不能因为网络问题把人卡在登录态里。
     */
    suspend fun logout(): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = api.logout()
            sessionStore.clear()
            if (response.isSuccess) {
                AppResult.Success(Unit)
            } else {
                AppResult.Failure(response.msg.ifBlank { "退出失败" }, response.code)
            }
        } catch (throwable: Throwable) {
            sessionStore.clear()
            Logger.w("Auth", "登出请求失败，本地会话已清理", throwable)
            AppResult.Success(Unit)
        }
    }

    /** 获取图形验证码。后端关闭验证码时 `captchaEnabled` 为 false。 */
    suspend fun loadCaptcha(): AppResult<CaptchaResponse> = withContext(Dispatchers.IO) {
        try {
            val response = api.captchaImage()
            if (response.isSuccess) {
                AppResult.Success(response)
            } else {
                AppResult.Failure(response.msg.ifBlank { "验证码加载失败" }, response.code)
            }
        } catch (throwable: Throwable) {
            throwable.toAppFailure()
        }
    }

    /** 本地是否已经有 token（只是读内存缓存，不校验有效性）。 */
    fun hasLocalToken(): Boolean = !sessionStore.token().isNullOrBlank()
}
