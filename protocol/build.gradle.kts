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
    // ⚠ `compileOnly`, and the reason is the whole point of this module.
    //
    // The thoth contract is JSON, and Android already ships `org.json` in the BOOT
    // classpath — so packaging the Maven artifact would put a second copy of those
    // classes in the APK that the platform then shadows. Compiling against it and
    // letting the device supply it keeps this module dependency-free where it counts
    // (nothing is added to :app's runtime) while the parsing still runs on a plain
    // JVM here, which is what the tests need.
    compileOnly(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.test {
    testLogging { showStandardStreams = false }
}
