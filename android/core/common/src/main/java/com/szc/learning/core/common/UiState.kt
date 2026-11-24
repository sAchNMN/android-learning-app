package com.szc.learning.core.common

/**
 * 通用 UI 状态。
 *
 * 刻意做成四态而不是三态：区分「还没请求」和「正在请求」，
 * 避免页面一进来就闪一下 loading。
 */
sealed interface UiState<out T> {

    /** 初始态：尚未发起请求。 */
    data object Idle : UiState<Nothing>

    /** 请求中。 */
    data object Loading : UiState<Nothing>

    /** 请求成功，[data] 可能为 null（例如登出接口无返回体）。 */
    data class Success<T>(val data: T?) : UiState<T>

    /** 请求失败，[code] 为业务错误码或 HTTP 状态码，[message] 可直接展示给用户。 */
    data class Error(val message: String, val code: Int = CODE_UNKNOWN) : UiState<Nothing>

    companion object {
        const val CODE_UNKNOWN: Int = -1
    }
}

/** 当前处于 Loading 态时返回 true，用于控制进度条显隐。 */
val UiState<*>.isLoading: Boolean
    get() = this is UiState.Loading

/** 取出成功态的数据，其它状态返回 null。 */
fun <T> UiState<T>.dataOrNull(): T? = (this as? UiState.Success)?.data
