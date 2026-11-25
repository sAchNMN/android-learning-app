import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
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
 * release 包的接口地址，必须显式传入：
 * ```
 * ./gradlew assembleRelease -PapiBaseUrlRelease=https://music.example.com/
 * ```
 *
 * 不编造域名：没传就退回占位地址并打印警告（不失败，否则 CI 里连体检都做不了）。
 * 占位地址一旦被误发到线上，所有请求都会打到 example.com —— 警告是要人看见的。
 */
val releaseApiBaseUrl: String = providers.gradleProperty("apiBaseUrlRelease").getOrElse("")

if (releaseApiBaseUrl.isBlank()) {
    logger.warn(
        "⚠️ release BASE_URL 仍是占位地址 https://api.example.com/；" +
            "正式打包请传 -PapiBaseUrlRelease=https://你的域名/",
    )
}

/**
 * release 签名。
 *
 * 密钥绝不进仓：优先读 gradle 属性 / `local.properties`（后者本来就被 gitignore）：
 * ```
 * -PstoreFile=/path/aurora-release.jks -PstorePassword=xxx -PkeyAlias=aurora -PkeyPassword=xxx
 * ```
 *
 * 都没提供时退回本机 debug keystore —— 目的只是让 **R8 之后的包能装到设备上冒烟**，
 * 不装包就验不了「推荐的 JSON 还解析得出来吗」这类只有混淆会弄坏的问题。
 * 用 debug 密钥签的包不能发版，构建时会打 warning。
 */
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String): String? =
    providers.gradleProperty(key).orNull ?: localProperties.getProperty(key)

val releaseStoreFile: String? = signingValue("storeFile")
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

if (releaseStoreFile == null && !debugKeystore.exists()) {
    logger.warn("⚠️ 没有 release 密钥也没有 debug.keystore，release 包将保持 unsigned、无法装机")
}

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

    // 必须写在 buildTypes 之前：release 里那句 findByName("release") 是配置期求值，
    // 写在后面会拿到 null（包就变 unsigned 且不报错）
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias")
                keyPassword = signingValue("keyPassword")
            }
        } else if (debugKeystore.exists()) {
            // 只为冒烟：debug 密钥签的包不能发版
            create("release") {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
            logger.warn("⚠️ release 用 debug keystore 签名，仅用于装机冒烟；正式发版请传 -PstoreFile=")
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            // 上线前请用 -PapiBaseUrlRelease= 传入真实域名，必须是 https。
            buildConfigField(
                "String",
                "BASE_URL",
                "\"${releaseApiBaseUrl.ifBlank { "https://api.example.com/" }}\"",
            )
            // AGP 9.3 起一个开关同时打开代码收缩、资源收缩与 proguard-android-optimize 默认规则，
            // 不要再配 isMinifyEnabled —— 两套 DSL 并存会让规则文件叠加两次
            optimization {
                enable = true
            }
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    // 首页轮播 + MV 竖向翻页都靠它（竖滑 ViewPager2 就是抖音那套交互）
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    // 播放内核：ExoPlayer + MediaSession（后台播放、通知栏控制、锁屏控制都靠它）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // 专辑封面异步加载（本地 content:// 与云端 http:// 同一套 API）
    implementation(libs.coil)
    // 封面取色：播放页背景跟着封面换色，Palette 是唯一官方实现，别自己写聚类
    implementation(libs.androidx.palette)
    // 安装时把 baseline profile 编译进 dex / 落进 ART，首帧与滚动预热更早
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

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
