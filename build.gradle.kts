// Top-level build file. Plugin versions are declared in gradle/libs.versions.toml
// and applied per-module. org.jetbrains.kotlin.android is intentionally absent:
// AGP 9.x provides built-in Kotlin, so only the Compose compiler plugin is needed
// alongside the Android application plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
