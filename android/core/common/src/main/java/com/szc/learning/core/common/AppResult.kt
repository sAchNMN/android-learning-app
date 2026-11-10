package com.szc.learning.core.common

import java.io.IOException

/**
 * 业务层统一结果：把「网络异常 / 业务错误 / 成功」压成一个类型，
 * 这样 ViewModel 里不需要到处 try-catch。
 */
sealed interface AppResult<out T> {

    data class Success<T>(val data: T) : AppResult<T>

    data class Failure(
        val message: String,
        val code: Int = CODE_UNKNOWN,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>

    companion object {
        const val CODE_UNKNOWN: Int = -1
        const val CODE_NETWORK: Int = -2
        const val CODE_PARSE: Int = -3

        /** 把异常统一翻译成用户能看懂的中文提示。 */
        fun from(throwable: Throwable): Failure = when (throwable) {
            is IOException -> Failure("网络连接失败，请检查网络后重试", CODE_NETWORK, throwable)
            else -> Failure(throwable.message ?: "未知错误", CODE_UNKNOWN, throwable)
        }
    }
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onFailure(action: (AppResult.Failure) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(this)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data
