plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.czk.sshai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.czk.sshai"
        minSdk = 24
        targetSdk = 35
        versionCode = 28
        versionName = "1.8.1"

        ndk {
            // FreeRDP 只编了 arm64-v8a（绝大多数在用手机都是这个）
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // DEX 原本约 45MB（未压缩），是 APK 体积的大头；开启 R8 后只保留
            // 实际触达的类。规则见 proguard-rules.pro（JNI 回调、Gson、JSch 需保留）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        jniLibs {
            // FreeRDP 的 .so 未压缩约 10.9MB，是 APK 另一半体积。
            // 压缩进 APK（安装时解压到 lib 目录），代价只是安装耗时略增。
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.google.code.gson:gson:2.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
