plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "io.github.jjmj.douyinunlimit"
    // Miuix 0.9.4 的 AAR metadata 要求 minCompileSdk = 37
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "io.github.jjmj.douyinunlimit"
        minSdk = 29
        targetSdk = 37
        versionCode = 17
        versionName = "1.9.1"
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
            // 之前这里是 false，理由是「反射 hook 怕被裁剪」——但那是错的：
            // 我们反射的是抖音的类（DuxToastV2 / ChatBanTipsLogic），R8 管不到别人的 APK。
            // 自己代码里唯一必须保命的是入口类 HookEntry（java_init.list 按全名加载），
            // proguard-rules.pro 里 keep 一行就够了。关掉 R8 白送 20MB。
            optimization.enable = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            vcsInfo.include = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // dex 默认不压缩（便于 mmap）。这是个设置页小 App，压一下能省一大截体积。
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.version",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
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
