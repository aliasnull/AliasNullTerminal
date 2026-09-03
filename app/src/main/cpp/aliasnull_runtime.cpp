// AliasNull native runtime bootstrap.
//
// THIS IS NOT A SHELL AND NOT A LINUX RUNTIME. This translation unit implements
// only the bootstrap foundation of the future AliasNull native runtime:
//
//   * initializeNativeRuntime  - validate the runtime root supplied by the app,
//                                verify the runtime-owned subdirectories exist
//                                and are writable, then record an honest
//                                "initialized" state.
//   * shutdownNativeRuntime    - release/clear the recorded bootstrap state.
//   * runtime/version/capabilities - expose only metadata that is genuinely real.
//
// No command is parsed or executed here. Command execution, stdin/stdout/stderr,
// the PTY and the Linux userspace are future phases and must not be simulated.
//
// JNI symbols use the standard mangled form of the Kotlin object
// NativeRuntimeBridge in package app.aliasnull.shell.runtime.native. These are
// INSTANCE methods on the singleton object, so the second parameter is jobject.

#include <jni.h>
#include <android/log.h>

#include <cstddef>
#include <mutex>
#include <string>

#include <sys/stat.h>
#include <unistd.h>

#define ALIASNULL_LOG_TAG "AliasNullNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, ALIASNULL_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, ALIASNULL_LOG_TAG, __VA_ARGS__)

namespace {

// Result codes returned across JNI to NativeRuntimeBridge. Values are a contract
// with the Kotlin side (see NativeRuntimeBridge.kt); keep them in sync.
constexpr jint kResultOk = 0;
constexpr jint kErrInvalidPath = -1;         // empty or non-absolute runtime root
constexpr jint kErrRootNotDirectory = -2;    // runtime root is not a directory
constexpr jint kErrSubdirMissing = -3;       // a required subdirectory does not exist
constexpr jint kErrNotWritable = -4;         // a required subdirectory is not writable
constexpr jint kErrAlreadyShutdown = -5;     // bootstrap requested after shutdown
constexpr jint kErrInternal = -99;           // anything unexpected

// Runtime-owned subdirectories, relative to the bootstrap root. These are the
// AliasNull runtime layout contract (mirrored in AliasNullNativeRuntime.kt), not
// Android-internal paths: the absolute root itself is always supplied by Kotlin.
constexpr const char* kRequiredSubdirs[] = {"state", "tmp", "metadata"};
constexpr size_t kRequiredSubdirCount = 3;

constexpr const char* kRuntimeVersion = "0.1.0";   // AliasNull native runtime version
constexpr const char* kBootstrapVersion = "1";     // this bootstrap foundation version
constexpr const char* kCapabilities =
    "nativeBootstrap,runtimeDirectoryValidation";  // only what is genuinely implemented

std::mutex gMutex;
bool gInitialized = false;
bool gShutdown = false;
std::string gRuntimeRoot;

bool isDirectory(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return false;
    return S_ISDIR(st.st_mode);
}

bool isWritableDirectory(const std::string& path) {
    return isDirectory(path) && access(path.c_str(), R_OK | W_OK | X_OK) == 0;
}

jint initializeNative(const std::string& root) {
    std::lock_guard<std::mutex> guard(gMutex);
    if (gShutdown) return kErrAlreadyShutdown;
    if (gInitialized) return kResultOk;  // idempotent: repeated initialization is safe

    if (root.empty() || root[0] != '/') return kErrInvalidPath;
    if (!isDirectory(root)) return kErrRootNotDirectory;

    std::string prefix = root;
    if (prefix.back() != '/') prefix += '/';
    for (size_t i = 0; i < kRequiredSubdirCount; ++i) {
        const std::string dir = prefix + kRequiredSubdirs[i];
        if (!isDirectory(dir)) return kErrSubdirMissing;
        if (!isWritableDirectory(dir)) return kErrNotWritable;
    }

    gRuntimeRoot = root;
    gInitialized = true;
    LOGI("AliasNull native runtime bootstrap initialized (root=%s)", gRuntimeRoot.c_str());
    return kResultOk;
}

void shutdownNative() {
    std::lock_guard<std::mutex> guard(gMutex);
    if (gShutdown) return;
    gShutdown = true;
    gInitialized = false;
    if (!gRuntimeRoot.empty()) {
        LOGI("AliasNull native runtime bootstrap shut down (root=%s)", gRuntimeRoot.c_str());
        gRuntimeRoot.clear();
    }
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeInitializeRuntime(
    JNIEnv* env, jobject /*thiz*/, jstring jRuntimeRootPath) {
    if (jRuntimeRootPath == nullptr) return kErrInvalidPath;
    const char* utf = env->GetStringUTFChars(jRuntimeRootPath, nullptr);
    if (utf == nullptr) return kErrInternal;
    std::string root(utf);
    env->ReleaseStringUTFChars(jRuntimeRootPath, utf);
    const jint result = initializeNative(root);
    if (result != kResultOk) {
        LOGE("AliasNull native bootstrap failed: code=%d root=%s", result, root.c_str());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeShutdown(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    shutdownNative();
}

JNIEXPORT jstring JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeRuntimeVersion(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(kRuntimeVersion);
}

JNIEXPORT jstring JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeBootstrapVersion(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(kBootstrapVersion);
}

JNIEXPORT jstring JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeCapabilities(
    JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF(kCapabilities);
}

}  // extern "C"
