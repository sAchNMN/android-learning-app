package com.szc.learning.core.network

import com.szc.learning.core.network.takeout.AddressApi
import com.szc.learning.core.network.takeout.TakeoutApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的网络入口。
 *
 * 只暴露 [authApi]，后续按业务加 `CourseApi`、`StudyApi` 等，
 * 都在这里 `retrofit.create(...)` 一次即可，共用连接池和拦截器。
 *
 * @param baseUrl 必须以 `/` 结尾，例如 `http://10.0.2.2:8080/`。
 * @param tokenProvider 同步返回当前 token，由 SessionStore 提供。
 * @param debug 是否打开 OkHttp 日志。使用 HEADERS 级别，
 *              避免把登录密码和响应体写进 Logcat。
 */
class ApiClient(
    baseUrl: String,
    tokenProvider: () -> String?,
    debug: Boolean = false,
) {

    private val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(AuthInterceptor(tokenProvider))
        .apply {
            if (debug) {
                addInterceptor(
                    HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    },
                )
            }
        }
        .build()

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(normalizedBaseUrl)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val authApi: AuthApi = retrofit.create(AuthApi::class.java)

    val takeoutApi: TakeoutApi = retrofit.create(TakeoutApi::class.java)

    val addressApi: AddressApi = retrofit.create(AddressApi::class.java)

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
