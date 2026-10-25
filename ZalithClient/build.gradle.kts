import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * ZL2 串流客户端。
 *
 * 这个模块刻意保持极简：
 *  - 不包含任何 Minecraft/JVM/渲染器代码，APK 体积小、安装快
 *  - 只做三件事：连接服务端、解码 H.264、回传输入
 *  - 因此 32 位低配设备也能跑得很顺
 */
android {
    namespace = "com.movtery.zalithlauncher.client"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.movtery.zalithlauncher.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 200045
        versionName = "2.7.1"
        //单个 APK 同时支持 arm32 与 arm64，省去用户挑包的麻烦
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            isMinifyEnabled = false
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                val suffix = if (variant.buildType == "release") "" else "-debug"
                output.outputFileName = "ZalithClient-2.7.1-arm-arm64$suffix.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(project(":StreamCore"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material3)
}
