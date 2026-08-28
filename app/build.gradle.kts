plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lionray.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lionray.vpn"
        minSdk = 29
        targetSdk = 36
        versionCode = 10200
        versionName = "1.2"
        ndk {
            // libgojni (libv2ray.aar) ships every ABI → one APK for all phones.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Xray-core now runs IN-PROCESS via libgojni (libv2ray.aar), which bundles
    // libgojni.so for arm64-v8a, armeabi-v7a, x86 and x86_64 — so a single
    // universal APK works on every phone. Core versions ship with the APK.
    implementation(files("libs/libv2ray.aar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
