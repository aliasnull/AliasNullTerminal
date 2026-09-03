// AliasNull application module.
//
// Part 26-G: libaliasnull_runtime.so is part of the normal build and is packaged
// for the supported ABI (arm64-v8a). Compiling it needs an Android NDK plus a
// CMake AGP can use; the build environment (ACS, or a local SDK with NDK+CMake
// installed) must provide them. The property below exists only as an emergency
// Kotlin-only fallback for a toolchain-less sandbox - no native library is
// produced when it is false:
//
//     ./gradlew :app:assembleDebug -Paliasnull.buildNative=false
//
// The Kotlin runtime bridge (shell/runtime/native) is independent of this flag:
// it always compiles and, when the library is absent, reports an honest
// bootstrap Error state instead of crashing.
val buildNativeRuntime: Boolean =
    providers.gradleProperty("aliasnull.buildNative").orNull?.toBoolean() ?: true

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.aliasnull"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.aliasnull"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        if (buildNativeRuntime) {
            // Build only for arm64-v8a for now: the target device is ARM64 and the
            // phase guidance is to support that ABI first without unnecessarily
            // building ABIs the environment cannot verify. Extend this set only
            // when a build environment can produce and verify those ABIs.
            ndk {
                abiFilters += "arm64-v8a"
            }
            // c++_static is set explicitly so <string>/<mutex> resolve regardless
            // of the NDK's default STL choice. ndkVersion is intentionally not
            // pinned: AGP resolves its own default NDK. Set android.ndkVersion if
            // a specific build environment ships a particular NDK.
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DANDROID_STL=c++_static")
                }
            }
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
        // Under AGP 9 built-in Kotlin, the Kotlin jvmTarget defaults to this value.
    }

    buildFeatures {
        compose = true
    }

    if (buildNativeRuntime) {
        // Builds the single AliasNull native runtime bootstrap library from the
        // NDK/CMake provided by the build environment (see src/main/cpp).
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)
}
