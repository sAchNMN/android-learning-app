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
 * 大多数接口把业务数据放在 `data` 里；但 `/login` 是个例外，
 * 它把 token 平铺在顶层，所以单独用 [LoginResponse] 承接。
 */
data class AjaxResult<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("data") val data: T? = null,
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS
}

/**
 * `/login` 的响应体。token 与 code/msg 同级，不在 data 里。
 * `expires_in` 为 token 剩余有效秒数，部分版本不返回，故可空。
 */
data class LoginResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("token") val token: String? = null,
    @SerializedName("expires_in") val expiresIn: Long? = null,
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS
}

/**
 * `/getInfo` 的响应体。user / roles / permissions 都是平铺的顶层字段。
 */
data class InfoResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("user") val user: UserInfo? = null,
    @SerializedName("roles") val roles: List<String> = emptyList(),
    @SerializedName("permissions") val permissions: List<String> = emptyList(),
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS
}

/**
 * `/captchaImage` 的响应体。`img` 是不带前缀的 Base64 PNG，
 * 使用时需自行拼 `data:image/png;base64,`。
 */
data class CaptchaResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("msg") val msg: String = "",
    @SerializedName("captchaEnabled") val captchaEnabled: Boolean = true,
    @SerializedName("uuid") val uuid: String? = null,
    @SerializedName("img") val img: String? = null,
) {
    val isSuccess: Boolean get() = code == CODE_SUCCESS
}
