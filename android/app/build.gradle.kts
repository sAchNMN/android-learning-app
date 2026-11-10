plugins {
    alias(libs.plugins.android.application)
}

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
            // 10.0.2.2 是 Android 模拟器访问宿主机 127.0.0.1 的固定地址，
            // 端口对应 RuoYi 后端 server.port（默认 8080）。
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8080/\"")
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
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
