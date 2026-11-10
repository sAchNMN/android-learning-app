package com.szc.learning

import android.app.Application
import com.szc.learning.core.common.Logger

/**
 * 应用入口。
 *
 * 只做两件事：打开日志开关、初始化依赖容器。
 * 依赖容器是手写的（没有 Hilt），好处是依赖关系一眼看穿，
 * 学习项目里比注解处理器更好调试。
 */
class LearningApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Logger.enabled = BuildConfig.DEBUG
        Logger.i("App", "启动，baseUrl=${BuildConfig.BASE_URL}")
        container = AppContainer(this)
    }
}
