import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

/**
 * 接口地址，可在命令行覆盖：
 * ```
 * ./gradlew assembleDebug -PapiBaseUrl=http://192.168.1.10:8080/
 * ```
 *
 * 默认走 `127.0.0.1` + `adb reverse tcp:8080 tcp:8080`（见下面的 adbReverse 任务），
 * 不依赖模拟器的 10.0.2.2 → 宿主机映射 —— 该映射在本机不生效，
 * 表现为 OkHttp 连 10.0.2.2:8080 干等 15 秒 connect timeout，而宿主机 curl 正常。
 * adb 隧道走的是 USB/socket 通道，绕开 QEMU NAT 和 Windows 防火墙，模拟器与真机通用。
 */
val debugApiBaseUrl: String =
    providers.gradleProperty("apiBaseUrl").getOrElse("http://127.0.0.1:8080/")

/** 后端端口，adb reverse 与 BASE_URL 必须一致，改端口改这里。 */
val backendPort: String = providers.gradleProperty("backendPort").getOrElse("8080")

/**
 * 定位 adb：`-PadbPath` → `ANDROID_HOME` / `ANDROID_SDK_ROOT` → `local.properties` 的 sdk.dir → PATH。
 * 找不到就退回裸 `adb`，由系统 PATH 兜底。
 */
fun resolveAdb(): String {
    providers.gradleProperty("adbPath").orNull?.let { return it }
    val exe = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
    val candidates = listOfNotNull(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
        runCatching {
            rootProject.file("local.properties").readLines()
                .first { it.trim().startsWith("sdk.dir") }
                .substringAfter("=").trim()
                .replace("\\:", ":")
                .replace("\\\\", "\\")
        }.getOrNull(),
    )
    return candidates
        .map { File(it, "platform-tools/$exe") }
        .firstOrNull { it.exists() }
        ?.absolutePath
        ?: "adb"
}

/**
 * 把设备的 [backendPort] 反向转发到宿主机。
 *
 * `installDebug` 会自动先跑这个任务，所以 `./gradlew installDebug` 一条命令即可。
 * adb server 或模拟器重启后规则会丢 —— 重跑本任务，或直接重跑 `installDebug`。
 * 没连设备时只提示不报错，不阻塞安装流程。
 */
val adbReverse = tasks.register<Exec>("adbReverse") {
    group = "install"
    description = "adb reverse tcp:$backendPort —— 让 App 用 127.0.0.1 访问宿主机后端"
    val adb = resolveAdb()
    val port = backendPort
    commandLine(adb, "reverse", "tcp:$port", "tcp:$port")
    isIgnoreExitValue = true
    doFirst { logger.lifecycle("→ adb reverse tcp:$port tcp:$port（adb: $adb）") }
}

// 装包前先把端口隧道建好，避免"装上了但连不上"。
tasks.matching { it.name == "installDebug" }.configureEach { dependsOn(adbReverse) }

android {
    namespace = "com.szc.learning"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.szc.learning"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            // 上线前替换为真实域名，必须是 https。
            buildConfigField("String", "BASE_URL", "\"https://api.example.com/\"")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    // 贪吃蛇 demo 模块已从构建中摘除，见 settings.gradle.kts
    // implementation(project(":feature:demo"))

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    // 购物车落盘（与 SessionStore 同一个 DataStore 库，各自一个文件名）
    implementation(libs.androidx.datastore.preferences)
    // 购物车落盘用 Gson 序列化（与网络层同一份实现，不另引序列化库）
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // ActivityScenario：仪器测试里直接拉起登录页并驱动 UI
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
