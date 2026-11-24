plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.szc.learning.feature.demo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)

    testImplementation(libs.junit)
}
