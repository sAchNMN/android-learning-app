package com.szc.learning.core.network

import com.szc.learning.core.common.AppResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 错误映射测试。
 *
 * 这层是「用户看到的提示」与「真实故障」之间的唯一翻译器，
 * 写错了会出现「登录失败但没有任何提示」或者「断网却提示密码错误」。
 */
class ErrorMapperTest {

    private fun httpError(code: Int, body: String = ""): HttpException =
        HttpException(
            Response.error<Any>(code, body.toResponseBody("application/json".toMediaType())),
        )

    @Test
    fun apiException_keepsBusinessCode() {
        val failure = ApiException(CODE_UNAUTHORIZED, "认证失败，无法访问系统资源").toAppFailure()

        assertEquals(CODE_UNAUTHORIZED, failure.code)
        assertEquals("认证失败，无法访问系统资源", failure.message)
    }

    @Test
    fun unauthorizedBody_isRecognizedAsBusinessFailure() {
        val failure = ApiException(401, "认证失败").toAppFailure()
        assertTrue(failure.code == CODE_UNAUTHORIZED)
    }

    @Test
    fun httpError_prefersServerMessage() {
        val failure = httpError(500, """{"code":500,"msg":"用户不存在/密码错误"}""").toAppFailure()

        assertEquals(500, failure.code)
        assertEquals("用户不存在/密码错误", failure.message)
    }

    @Test
    fun httpError_fallsBackWhenBodyNotJson() {
        val failure = httpError(404, "<html>not found</html>").toAppFailure()

        assertEquals(404, failure.code)
        assertEquals("接口不存在，请检查服务端版本", failure.message)
    }

    @Test
    fun http401_mapsToSessionExpiredHint() {
        val failure = httpError(401).toAppFailure()
        assertEquals("登录已过期，请重新登录", failure.message)
    }

    @Test
    fun networkExceptions_mapToReadableHint() {
        val cases = listOf(
            UnknownHostException("api.example.com"),
            SocketTimeoutException("timeout"),
            IOException("broken pipe"),
        )
        for (error in cases) {
            val failure = error.toAppFailure()
            assertEquals(AppResult.CODE_NETWORK, failure.code)
            assertEquals("网络连接失败，请检查网络后重试", failure.message)
        }
    }

    @Test
    fun unknownError_doesNotLeakNullMessage() {
        val failure = IllegalStateException().toAppFailure()
        assertEquals(AppResult.CODE_UNKNOWN, failure.code)
        assertEquals("未知错误", failure.message)
    }
}
