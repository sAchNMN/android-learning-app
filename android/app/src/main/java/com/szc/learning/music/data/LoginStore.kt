package com.szc.learning.music.data

import android.content.Context

/**
 * 首次启动的登录选择。
 *
 * 只记两件事：登录页是否走完、用户最终从哪个入口进来。
 * 平台凭证不在这里 —— 它属于 [SourceConfigStore]，本类不碰 Cookie。
 *
 * 用 SharedPreferences 而不是 DataStore：两个布尔/字符串字段，
 * 且 MainActivity 要在 onCreate 里同步判断，挂起调用会把首屏让位逻辑变复杂。
 */
class LoginStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 登录页是否已完成。false 时 MainActivity 会把用户送回登录页。 */
    val isComplete: Boolean
        get() = prefs.getBoolean(KEY_COMPLETE, false)

    /** 用户选择进入的入口：两个在线平台之一，或 [MODE_LOCAL]。 */
    val mode: String
        get() = prefs.getString(KEY_MODE, MODE_LOCAL).orEmpty()

    fun complete(mode: String) {
        prefs.edit()
            .putBoolean(KEY_COMPLETE, true)
            .putString(KEY_MODE, mode)
            .apply()
    }

    companion object {
        const val MODE_NETEASE = "netease"
        const val MODE_KUGOU = "kugou"
        const val MODE_LOCAL = "local"

        private const val PREFS = "aurora.login"
        private const val KEY_COMPLETE = "complete"
        private const val KEY_MODE = "mode"
    }
}
