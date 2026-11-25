package com.szc.learning.core.network

import com.szc.learning.core.network.music.MusicApi
import com.szc.learning.core.network.takeout.AddressApi
import com.szc.learning.core.network.takeout.ShopApi
import com.szc.learning.core.network.takeout.TakeoutApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的网络入口。
 *
 * 后端是**可选**的：只有分享码和歌曲评论这类需要中心化数据的功能非它不可，
 * 其余能力（本地曲库、在线音源、推荐、收藏）都在客户端自己完成。
 * 所以这里没有认证头，也没有登录会话 —— App 里不存在"当前用户"这个概念。
 *
 * @param baseUrl 必须以 `/` 结尾，例如 `http://127.0.0.1:8080/`
 *                （本机调试走 `adb reverse`，见 `app/build.gradle.kts` 的 adbReverse 任务）。
 * @param debug 是否打开 OkHttp 日志。使用 HEADERS 级别，避免把响应体写进 Logcat。
 */
class ApiClient(
    baseUrl: String,
    debug: Boolean = false,
) {

    private val normalizedBaseUrl: String = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private val okHttpClient: OkHttpClient = SharedHttp.base.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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

    val takeoutApi: TakeoutApi = retrofit.create(TakeoutApi::class.java)

    val addressApi: AddressApi = retrofit.create(AddressApi::class.java)

    val shopApi: ShopApi = retrofit.create(ShopApi::class.java)

    val musicApi: MusicApi = retrofit.create(MusicApi::class.java)

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
