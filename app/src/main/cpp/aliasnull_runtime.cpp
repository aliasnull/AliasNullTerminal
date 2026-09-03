// AliasNull native runtime bootstrap.
//
// THIS IS NOT A SHELL AND NOT A LINUX RUNTIME. This translation unit implements
// the bootstrap foundation of the future AliasNull native runtime plus a small,
// honest process/PTY *foundation*:
//
//   * initializeNativeRuntime  - validate the runtime root supplied by the app,
//                                verify the runtime-owned subdirectories exist
//                                and are writable, then record an honest
//                                "initialized" state.
//   * shutdownNativeRuntime    - release/clear the recorded bootstrap state.
//   * create/state/count/close - lifecycle of opaque runtime "session slots".
//                                A session is a placeholder identity with a
//                                lifecycle state. It is NOT an OS process and NOT
//                                a PTY: creating one never spawns anything, and a
//                                session never reports RUNNING in this phase.
//   * runtime/version/capabilities - expose only metadata that is genuinely real.
//
// No command is parsed or executed here, and no process, fork, PTY, shell,
// stdin/stdout/stderr or Linux userspace is created or simulated. Those are
// future phases and a session slot must never be mistaken for any of them.
//
// JNI symbols use the standard mangled form of the Kotlin object
// NativeRuntimeBridge in package app.aliasnull.shell.runtime.native. These are
// INSTANCE methods on the singleton object, so the second parameter is jobject.

#include <jni.h>
#include <android/log.h>

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

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
constexpr const char* kBootstrapVersion = "2";     // bootstrap foundation version (2 adds the session foundation)
constexpr const char* kCapabilities =
    "nativeBootstrap,runtimeDirectoryValidation,nativeSessionFoundation";  // only what is genuinely implemented

// Session-layer result/state codes returned across JNI. State values form the
// declared lifecycle vocabulary of a foundation session slot. Only READY and
// CLOSED are reachable in this phase: STARTING and RUNNING are reserved for the
// future real process/PTY phase and must never be set here.
constexpr jint kSessionStateUninitialized = 0;
constexpr jint kSessionStateReady = 1;
constexpr jint kSessionStateStarting = 2;  // reserved - never set by this foundation
constexpr jint kSessionStateRunning = 3;   // reserved - never set by this foundation
constexpr jint kSessionStateClosed = 4;
constexpr jint kSessionStateError = 5;
constexpr jint kSessionNotFound = -1;        // query for an id that has no live session
constexpr jint kSessionLayerStopped = -2;    // close requested after the layer shut down

std::mutex gMutex;
bool gInitialized = false;
bool gShutdown = false;
std::string gRuntimeRoot;

// Session-slot foundation state. Sessions are opaque placeholder identities for a
// future execution backend; nothing is forked or spawned when one is created.
// gNextSessionId is monotonic and never reused so ids stay stable and opaque.
std::mutex gSessionMutex;
std::unordered_map<int64_t, jint> gSessions;  // live session id -> lifecycle state
int64_t gNextSessionId = 1;

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

// Creates one placeholder session slot. Returns its stable opaque id, or 0 when
// the runtime is not bootstrapped/shut down or the slot could not be reserved.
// The slot starts in READY ("awaiting a future execution binding"); it never
// spawns anything and never transitions to RUNNING in this phase.
int64_t createSession() {
    {
        std::lock_guard<std::mutex> guard(gMutex);
        if (!gInitialized || gShutdown) return 0;
    }
    std::lock_guard<std::mutex> guard(gSessionMutex);
    const int64_t id = gNextSessionId++;
    gSessions.emplace(id, kSessionStateReady);
    LOGI("AliasNull foundation session created: id=%lld state=READY (placeholder; nothing is running)",
         static_cast<long long>(id));
    return id;
}

// Lifecycle state of a live session, or kSessionNotFound for an id with no live
// session (never created, or already closed and removed).
jint sessionState(int64_t id) {
    std::lock_guard<std::mutex> guard(gSessionMutex);
    const auto it = gSessions.find(id);
    if (it == gSessions.end()) return kSessionNotFound;
    return it->second;
}

// Number of currently live session slots. Returns 0 once every slot is closed.
jint activeSessionCount() {
    std::lock_guard<std::mutex> guard(gSessionMutex);
    return static_cast<jint>(gSessions.size());
}

// Closes a session slot deterministically. A live slot is removed and retired;
// an unknown or already-closed id is an idempotent no-op (both return 0). The
// only non-zero return means the session layer has already been shut down.
jint closeSession(int64_t id) {
    {
        std::lock_guard<std::mutex> guard(gMutex);
        if (gShutdown) return kSessionLayerStopped;
    }
    std::lock_guard<std::mutex> guard(gSessionMutex);
    const auto it = gSessions.find(id);
    if (it == gSessions.end()) {
        LOGI("AliasNull foundation session close: id=%lld already closed or unknown (no-op)",
             static_cast<long long>(id));
        return 0;
    }
    gSessions.erase(it);
    LOGI("AliasNull foundation session closed: id=%lld", static_cast<long long>(id));
    return 0;
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

JNIEXPORT jlong JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeCreateSession(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return static_cast<jlong>(createSession());
}

JNIEXPORT jint JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeSessionState(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionId) {
    return sessionState(static_cast<int64_t>(sessionId));
}

JNIEXPORT jint JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeActiveSessionCount(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    return activeSessionCount();
}

JNIEXPORT jint JNICALL
Java_app_aliasnull_shell_runtime_native_NativeRuntimeBridge_nativeCloseSession(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionId) {
    return closeSession(static_cast<int64_t>(sessionId));
}

}  // extern "C"
