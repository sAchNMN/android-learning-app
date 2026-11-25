package com.szc.learning

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.szc.learning.core.common.Logger
import com.szc.learning.music.ui.common.AudioArtFetcher
import com.szc.learning.ui.theme.ThemeManager

/**
 * 应用入口。
 *
 * 四件事：打开日志、初始化依赖容器、装配图片加载器、恢复上次的亮暗模式。
 *
 * 依赖容器是手写的（没有 Hilt），依赖关系一眼看穿，学习项目里比注解处理器更好调试。
 */
class LearningApp : Application(), ImageLoaderFactory, Application.ActivityLifecycleCallbacks {

    lateinit var container: AppContainer
        private set

    /** 处于 started 状态的 Activity 数：>0 就是有前台页面。 */
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        // 必须最早：赶在任何 Activity 创建之前把明暗模式定下来，
        // 否则冷启动的第一次 draw 会用系统默认模式，然后闪一下再翻过来
        ThemeManager.applyModeOnLaunch(this)
        Logger.enabled = BuildConfig.DEBUG
        Logger.i("App", "启动，baseUrl=${BuildConfig.BASE_URL}")
        container = AppContainer(this)
        // 后台探测（P1-5）靠它判断有没有前台页面，不引入 ProcessLifecycleOwner
        registerActivityLifecycleCallbacks(this)
    }

    // ---------------------------------------------------------------- 前后台

    override fun onActivityStarted(activity: Activity) {
        // 首个页面出来后再连播放服务：冷启动时播放器是空队列，不必抢在首帧之前
        container.playerController.connect()
        startedActivities++
        if (startedActivities == 1) container.onlineSourceRouter.setForeground(true)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) container.onlineSourceRouter.setForeground(false)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit

    /**
     * 全局 Coil 配置。
     *
     * 唯一的定制是 [AudioArtFetcher]：本地音频的封面藏在 ID3 里，
     * 必须用 loadThumbnail 解，默认的 Fetcher 只会把它当图片文件去解码然后失败。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(AudioArtFetcher.Factory(contentResolver)) }
        .crossfade(true)
        // 曾经关掉硬件位图是为了「玻璃高斯模糊要把容器画进软件画布」，那条路径已经删除。
        // API 26+ 由 Coil 自动用硬件位图；需要退回软件位图时改这个开关即可。
        .allowHardware(ALLOW_HARDWARE_BITMAPS)
        .build()

    private companion object {
        const val ALLOW_HARDWARE_BITMAPS = true
    }
}
