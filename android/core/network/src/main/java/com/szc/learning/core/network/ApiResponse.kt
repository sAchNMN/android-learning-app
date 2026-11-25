package com.szc.learning.core.network

import com.google.gson.annotations.SerializedName

/** RuoYi 约定的成功码。注意不是 0，也不是 HTTP 200 的字面复用。 */
const val CODE_SUCCESS: Int = 200

/** 认证失败：RuoYi 会以 HTTP 200 + body code=401 的形式返回。 */
const val CODE_UNAUTHORIZED: Int = 401

/** 无权限。 */
const val CODE_FORBIDDEN: Int = 403

/**
 * 对齐 RuoYi 的 `AjaxResult`：
 * ```
 * { "code": 200, "msg": "操作成功", "data": { ... } }
 * ```
 *
 * App 没有登录体系，所以这里只有这一种响应包装 —— `/login`、`/getInfo` 那套
 * 图形验证码 + token 的结构随认证代码一并移除了。
 */
data class AjaxResult<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("data") val data: T? = null,
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS
}
