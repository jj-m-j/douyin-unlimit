plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.jjmj.douyinunlimit"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.jjmj.douyinunlimit"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    val keystorePath = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        register("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            // 模块大量依赖反射 hook，关掉 R8 以免类名/方法被裁剪
            optimization.enable = false
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // 由框架在运行时提供
    compileOnly(libs.libxposed.api)
    // 需要打进 APK：XposedProvider 会在清单里自动合并
    implementation(libs.libxposed.service)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
}
