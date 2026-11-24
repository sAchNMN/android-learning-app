package com.szc.learning.core.network

import com.google.gson.annotations.SerializedName

/**
 * RuoYi `sys_user` 对外的精简映射。
 *
 * 只保留 App 真正会用到的字段。后端加字段不影响解析，
 * 这里不声明即可；需要时再补。
 */
data class UserInfo(
    @SerializedName("userId") val userId: Long = 0,
    @SerializedName("deptId") val deptId: Long? = null,
    @SerializedName("userName") val userName: String = "",
    @SerializedName("nickName") val nickName: String = "",
    @SerializedName("email") val email: String? = null,
    @SerializedName("phonenumber") val phoneNumber: String? = null,
    @SerializedName("sex") val sex: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("createTime") val createTime: String? = null,
) {
    /** 优先展示昵称，没有再退回登录名。 */
    val displayName: String get() = nickName.ifBlank { userName }
}

/** 登录请求体，字段名与 RuoYi 的 `LoginBody` 一一对应。 */
data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    /** 验证码，后端关闭验证码时传 null 即可。 */
    @SerializedName("code") val code: String? = null,
    /** 验证码标识，与 [code] 成对出现。 */
    @SerializedName("uuid") val uuid: String? = null,
)

/**
 * 当前登录会话的完整信息。
 *
 * 把 `user` 和权限放在一起返回，是因为 `/getInfo` 本来就是一次请求拿全的，
 * 拆成两次会让首页要么缺角色、要么多发一次请求。
 */
data class CurrentUser(
    val info: UserInfo,
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
) {
    /** 是否拥有超级管理员权限，用于界面上的兜底判断。 */
    val isAdmin: Boolean get() = roles.contains("admin") || permissions.contains("*:*:*")
}

