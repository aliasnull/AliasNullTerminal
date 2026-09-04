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

// Part 27-B Rust AN Shell core: the crate lives at the repository root under
// rust/aliasnull_an_shell_core and is cross-compiled for arm64-v8a by the
// compileAnShellCoreRust task. The produced .so is staged under this module's
// build dir and merged into the APK by AGP (see the sourceSets block below).
// Both the C++ runtime and the Rust core are governed by buildNativeRuntime, so
// the Kotlin-only fallback build disables them together and stays consistent.
val anShellCoreRustDir = rootProject.file("rust/aliasnull_an_shell_core")
val anShellCoreJniLibsFile = layout.buildDirectory.dir("rust/jniLibs").get().asFile

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

    sourceSets {
        getByName("main") {
            // Part 27-B: AGP merges every arm64-v8a *.so staged under this
            // directory into the APK. compileAnShellCoreRust populates it; when
            // buildNativeRuntime is false the directory simply stays empty,
            // which is harmless.
            jniLibs.srcDir(anShellCoreJniLibsFile)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

if (buildNativeRuntime) {
    // Part 27-B: make the Rust build a real dependency of the Android build.
    // compileAnShellCoreRust cross-compiles the Rust cdylib for Android
    // arm64-v8a with cargo-ndk and stages it where the source set above merges
    // it into the APK, so a Rust compile failure fails the APK build and a
    // stale artifact can never satisfy the build. The Rust stable toolchain
    // with the aarch64-linux-android target plus cargo-ndk are documented
    // prerequisites (see rust-toolchain.toml); CI installs them, and the NDK is
    // located through ANDROID_NDK_HOME / ANDROID_NDK_ROOT.
    val compileAnShellCoreRust = tasks.register<Exec>("compileAnShellCoreRust") {
        group = "build"
        description = "Cross-compiles the AliasNull AN Shell core Rust cdylib for Android arm64-v8a and stages it for packaging."
        workingDir(anShellCoreRustDir)
        doFirst {
            val ndkHome = System.getenv("ANDROID_NDK_HOME")
            val ndkRoot = System.getenv("ANDROID_NDK_ROOT")
            if (ndkHome.isNullOrEmpty() && ndkRoot.isNullOrEmpty()) {
                throw GradleException(
                    "compileAnShellCoreRust needs the Android NDK. Set ANDROID_NDK_HOME " +
                        "(or ANDROID_NDK_ROOT), e.g. to \$ANDROID_SDK_ROOT/ndk/28.2.13676358, " +
                        "and have the Rust stable toolchain with the aarch64-linux-android " +
                        "target plus cargo-ndk installed (see rust-toolchain.toml).",
                )
            }
        }
        // cargo-ndk places the cdylib at <out>/arm64-v8a/libaliasnull_an_shell_core.so.
        commandLine(
            "cargo", "ndk",
            "-t", "arm64-v8a",
            "-o", anShellCoreJniLibsFile.absolutePath,
            "build", "--release",
        )
        doLast {
            val produced = anShellCoreJniLibsFile.resolve("arm64-v8a/libaliasnull_an_shell_core.so")
            if (!produced.isFile) {
                throw GradleException(
                    "compileAnShellCoreRust ran but did not produce the expected artifact: " +
                        produced.absolutePath,
                )
            }
        }
    }

    tasks.named("preBuild") {
        dependsOn(compileAnShellCoreRust)
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
