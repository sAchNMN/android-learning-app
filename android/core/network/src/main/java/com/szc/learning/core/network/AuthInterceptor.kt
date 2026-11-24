package com.szc.learning.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 统一注入认证头。
 *
 * RuoYi 的 `TokenService` 从 `Authorization` 头取值并自行剥离 `Bearer ` 前缀，
 * 所以这里必须带上前缀，否则后端会把 "Bearer xxx" 整体当成 token 去查 Redis。
 */
class AuthInterceptor(
    private val tokenProvider: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val original = chain.request()

        if (token.isNullOrBlank()) {
            return chain.proceed(original)
        }

        val authorized = original.newBuilder()
            .header(HEADER_AUTHORIZATION, "$BEARER_PREFIX$token")
            .build()
        return chain.proceed(authorized)
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "
    }
}
