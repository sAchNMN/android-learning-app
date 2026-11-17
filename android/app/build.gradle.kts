plugins {
    alias(libs.plugins.android.application)
}

/**
 * 接口地址，可在命令行覆盖：
 * ```
 * ./gradlew assembleDebug -PapiBaseUrl=http://127.0.0.1:8080/
 * ```
 *
 * 默认 10.0.2.2 是 Android 模拟器访问宿主机回环的标准地址。
 * 但部分机器上 QEMU 的该映射不生效（表现为连接超时、而局域网 IP 可达），
 * 此时用 `adb reverse tcp:8080 tcp:8080` + `127.0.0.1` 更可靠，不受防火墙和 IP 变动影响。
 */
val debugApiBaseUrl: String =
    providers.gradleProperty("apiBaseUrl").getOrElse("http://10.0.2.2:8080/")

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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    // ActivityScenario：仪器测试里直接拉起登录页并驱动 UI
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
}
