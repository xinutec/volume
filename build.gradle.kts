// Root build script: declares the plugins :app applies. Versions are centralised
// in gradle/libs.versions.toml. Mirrors govee-android, minus Compose — the probe
// has no UI worth a framework.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
