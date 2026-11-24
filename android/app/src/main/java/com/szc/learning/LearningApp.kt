package com.szc.learning

import android.app.Application
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
class LearningApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 必须最早：赶在任何 Activity 创建之前把明暗模式定下来，
        // 否则冷启动的第一次 draw 会用系统默认模式，然后闪一下再翻过来
        ThemeManager.applyModeOnLaunch(this)
        Logger.enabled = BuildConfig.DEBUG
        Logger.i("App", "启动，baseUrl=${BuildConfig.BASE_URL}")
        container = AppContainer(this)
    }

    /**
     * 全局 Coil 配置。
     *
     * 唯一的定制是 [AudioArtFetcher]：本地音频的封面藏在 ID3 里，
     * 必须用 loadThumbnail 解，默认的 Fetcher 只会把它当图片文件去解码然后失败。
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(AudioArtFetcher.Factory(contentResolver)) }
        .crossfade(true)
        // 玻璃的"高斯模糊"模式会把页面容器重画进一张小画布再模糊。
        // 硬件位图画不进软件画布（会抛 IllegalArgumentException），
        // 关掉 allowHardware 之后所有封面都是软位图，采样才稳定。
        .allowHardware(false)
        .build()
}
