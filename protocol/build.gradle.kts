// The device-independent half. No Android plugin, no SDK, no emulator: `./gradlew
// :protocol:test` runs on any JVM, which is the point — the wire formats are where
// most of the code is, and they need a phone only to be *discovered*, not to be
// checked.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    testLogging { showStandardStreams = false }
}
