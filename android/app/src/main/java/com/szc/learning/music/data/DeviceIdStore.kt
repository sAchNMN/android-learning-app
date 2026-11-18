package com.szc.learning.music.data

import android.content.Context
import java.util.UUID

/**
 * 设备号：App 没有登录体系，这个 UUID 就是「我是谁」。
 *
 * 后端用它给收藏记录归属，换设备/重装就会换一个号 —— 这是刻意的取舍：
 * 课设场景下为了跨设备同步去引入一套账号体系，成本远大于收益。
 * 首次启动生成一次，之后一直复用。
 */
class DeviceIdStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val id: String by lazy {
        prefs.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ID, it).apply()
        }
    }

    private companion object {
        const val PREFS = "aurora_device"
        const val KEY_ID = "device_id"
    }
}
