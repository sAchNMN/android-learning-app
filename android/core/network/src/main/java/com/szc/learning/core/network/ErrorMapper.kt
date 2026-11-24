package com.szc.learning.core.network

import com.google.gson.JsonParser
import com.szc.learning.core.common.AppResult
import retrofit2.HttpException
import java.io.IOException

/**
 * 把网络层异常统一翻译成 [AppResult.Failure]。
 *
 * 关键点：RuoYi 的业务失败（含未登录）通常走 HTTP 200 + body `code != 200`，
 * 这类失败由 [ApiException] 承载；只有网关、代理或后端崩溃才会抛 [HttpException]。
 * 两条路径都要覆盖，否则用户会看到「登录失败但没有任何提示」。
 */
internal fun Throwable.toAppFailure(): AppResult.Failure = when (this) {
    is ApiException -> AppResult.Failure(message, code, this)

    is HttpException -> {
        val serverMsg = runCatching {
            val raw = response()?.errorBody()?.string().orEmpty()
            JsonParser.parseString(raw).asJsonObject.get("msg")?.asString
        }.getOrNull()

        val fallback = when (code()) {
            401 -> "登录已过期，请重新登录"
            403 -> "没有访问权限"
            404 -> "接口不存在，请检查服务端版本"
            in 500..599 -> "服务器开小差了，请稍后重试"
            else -> "请求失败（HTTP ${code()}）"
        }
        AppResult.Failure(serverMsg?.takeIf { it.isNotBlank() } ?: fallback, code(), this)
    }

    is IOException -> AppResult.Failure(
        "网络连接失败，请检查网络后重试",
        AppResult.CODE_NETWORK,
        this,
    )

    else -> AppResult.Failure(message ?: "未知错误", AppResult.CODE_UNKNOWN, this)
}
