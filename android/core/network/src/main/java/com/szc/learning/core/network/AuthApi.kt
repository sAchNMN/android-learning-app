package com.szc.learning.core.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * RuoYi 认证相关接口。
 *
 * 这些路径没有 `/api` 之类前缀，因为 RuoYi 默认 `server.servlet.context-path`
 * 为空，接口直接挂在 8080 根路径上。若后端加了 context-path，
 * 在 [ApiClient] 的 baseUrl 里补上即可，这里不用改。
 */
interface AuthApi {

    /** 账号密码登录，返回 token。 */
    @POST("login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    /** 退出登录，服务端会删除 Redis 里的 token。 */
    @POST("logout")
    suspend fun logout(): AjaxResult<Any>

    /** 获取当前登录用户信息、角色和权限。 */
    @GET("getInfo")
    suspend fun getInfo(): InfoResponse

    /** 获取图形验证码。后端关闭验证码时 `captchaEnabled` 为 false。 */
    @GET("captchaImage")
    suspend fun captchaImage(): CaptchaResponse
}
