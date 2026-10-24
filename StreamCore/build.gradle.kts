plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.movtery.streamcore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
}
