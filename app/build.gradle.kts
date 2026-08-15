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
        // minSdk 31 (Android 12): BLUETOOTH_CONNECT as a runtime permission lands
        // here, so the pre-12 legacy BLUETOOTH model is out of scope entirely. The
        // target phone is the Pixel 9 on Android 17.
        minSdk = 31
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
