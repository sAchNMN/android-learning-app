package com.szc.learning.core.common

import android.util.Log

/**
 * 轻量日志门面。
 *
 * [enabled] 由 app 模块在 Application 启动时按 BuildConfig.DEBUG 赋值，
 * 这样 release 包不会泄漏请求参数和 token。
 */
object Logger {

    @Volatile
    var enabled: Boolean = false

    private const val TAG_PREFIX = "Learning/"

    fun d(tag: String, message: String) {
        if (enabled) Log.d(TAG_PREFIX + tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(TAG_PREFIX + tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) Log.w(TAG_PREFIX + tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (enabled) Log.e(TAG_PREFIX + tag, message, throwable)
    }
}
