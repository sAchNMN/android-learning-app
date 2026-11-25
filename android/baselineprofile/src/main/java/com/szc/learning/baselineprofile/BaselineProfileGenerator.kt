package com.szc.learning.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 采集冷启动路径的 Baseline Profile。
 *
 * 只跑「冷启动到首帧」这一条：它覆盖 Application/Activity/主题/首屏渲染，
 * 也是用户每次打开 App 都会走的路径。其他交互路径的数据量小、收益不稳定，
 * 先不采，避免把 profile 撑得太大反而拖慢 dex 布局。
 *
 * 生成命令：
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun coldStartup() {
        rule.collect(
            packageName = "com.szc.learning",
            // 把首帧之前执行的方法同时记成 startup profile：R8 用它安排 dex 布局，
            // 冷启动时类加载更早命中。
            includeInStartupProfile = true,
            maxIterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
        }
    }
}
