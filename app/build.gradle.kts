plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本号可由构建参数注入（CI 从 tag 取值），本地构建用默认值
val appVersionName = (findProperty("appVersionName") as String?)?.takeIf { it.isNotBlank() } ?: "1.0"
val appVersionCode = ((findProperty("appVersionCode") as String?) ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.example.chatshell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.chatshell"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
