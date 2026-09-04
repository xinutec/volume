plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
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

    buildFeatures {
        compose = true
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

// Compose for VolumeActivity, the app someone actually looks at. MainActivity —
// the #783 probe — stays a bare TextView on purpose: it is driven by intent and
// read from logcat, and giving an instrument a UI framework buys nothing.
//
// No AppCompat: Material 3 in Compose does not need it, and the one thing it would
// add here is a second theming system to keep in step.
dependencies {
    // The wire formats and the thoth contract live in :protocol, which has no Android
    // dependency and is tested on the JVM. :app is only transports, scanning and screen.
    implementation(project(":protocol"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    testImplementation(libs.junit)
    // The mockable android.jar's `org.json` throws on every call. A unit test that
    // reaches thoth parsing through :protocol would fail for that reason and no
    // other, so the real implementation goes on the test classpath ahead of it.
    testImplementation(libs.json)
}
