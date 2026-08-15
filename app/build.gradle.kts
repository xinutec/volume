plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.xinutec.volume"
    compileSdk = 36
    // Pin to the build-tools the nix SDK provides (AGP would otherwise default to a
    // version that isn't present and can't be auto-installed into the read-only SDK).
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.xinutec.volume"
        // minSdk 33 (Android 13). 31 would do for BLUETOOTH_CONNECT, but the GATT
        // calls Gatt.kt needs — writeCharacteristic(char, value, type),
        // writeDescriptor(desc, value), onCharacteristicChanged(g, c, value) — all
        // land in 33, and their pre-33 forms pass the payload through mutable state on
        // the characteristic, which races. The target phone is a Pixel 9 on Android 17,
        // so raising the floor costs nothing and removes the racy branch entirely.
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Sideloaded probe — no shrinking, debug key.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// No Compose, no AppCompat, no core-ktx. The probe's screen is a TextView built in
// code; everything it does is driven by intent and read back from logcat, so a UI
// dependency would be weight with no reader.
dependencies {
    testImplementation(libs.junit)
}
