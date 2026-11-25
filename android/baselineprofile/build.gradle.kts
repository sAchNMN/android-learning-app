plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.androidx.baselineprofile)
}

/**
 * Baseline Profile 生成模块。
 *
 * 它不参与 APK 打包：只在设备/模拟器上跑 macrobenchmark 的
 * `BaselineProfileRule`，把常见启动路径（冷启动 → 首帧）的 ART 热点采集下来。
 * 生成的 `baseline-prof.txt` 由 `androidx.baselineprofile` 插件合并进 app。
 */
android {
    namespace = "com.szc.learning.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
