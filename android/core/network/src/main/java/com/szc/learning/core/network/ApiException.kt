package com.szc.learning.core.network

/**
 * 业务侧异常：由响应体里的 `code` 判定，不是 HTTP 层异常。
 *
 * RuoYi 把「未登录 / 无权限 / 参数错误」都塞在 200 状态码的响应体里，
 * 所以必须靠这个异常把业务失败和网络失败区分开。
 */
class ApiException(
    val code: Int,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    val isUnauthorized: Boolean get() = code == CODE_UNAUTHORIZED
    val isForbidden: Boolean get() = code == CODE_FORBIDDEN
}
