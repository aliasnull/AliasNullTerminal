package app.aliasnull.shell.runtime.native

import android.util.Log

/**
 * The single owner of the Kotlin <-> native boundary for
 * libaliasnull_runtime.so.
 *
 * Every [System.loadLibrary] call and every JNI entry point is confined to this
 * object so the rest of the app never touches JNI names and the library can
 * never be loaded twice from different places. Loading happens only when the
 * runtime is being initialized (see [AliasNullNativeRuntime]), never eagerly at
 * app start, and a load failure is reported as a structured
 * [NativeRuntimeResult] instead of crashing the app.
 *
 * The external declarations below map to the mangled symbols implemented in
 * app/src/main/cpp/aliasnull_runtime.cpp; keep names and signatures in sync with
 * that file. This object is a singleton, so its native methods are instance
 * methods (the C++ second parameter is jobject).
 */
internal object NativeRuntimeBridge {

    private const val TAG = "AliasNullNativeBridge"
    private const val LIBRARY_NAME = "aliasnull_runtime"

    @Volatile
    private var libraryLoaded = false

    private val lock = Any()

    val isLibraryLoaded: Boolean get() = libraryLoaded

    /**
     * Loads libaliasnull_runtime.so exactly once. Safe to call repeatedly and
     * from any thread; returns false (never throws) when the library cannot be
     * loaded, so an unsupported ABI or missing .so degrades to an honest error
     * state instead of a crash.
     */
    fun ensureLibraryLoaded(): Boolean {
        if (libraryLoaded) return true
        synchronized(lock) {
            if (libraryLoaded) return true
            Log.i(TAG, "Native library load started: aliasnull_runtime")
            libraryLoaded = try {
                System.loadLibrary(LIBRARY_NAME)
                Log.i(TAG, "libaliasnull_runtime.so loaded successfully")
                true
            } catch (t: UnsatisfiedLinkError) {
                Log.e(TAG, "Native library load failed (library missing or ABI unsupported)", t)
                false
            } catch (t: SecurityException) {
                Log.e(TAG, "Native library load blocked", t)
                false
            }
        }
        return libraryLoaded
    }

    /**
     * Calls the native bootstrap. Assumes the caller has already prepared and
     * validated the runtime directories under [runtimeRootPath]; the native side
     * re-validates the same layout before recording an initialized state.
     */
    fun initializeNativeRuntime(runtimeRootPath: String): NativeRuntimeResult {
        if (!ensureLibraryLoaded()) {
            return NativeRuntimeResult.failure(
                NativeBootstrapCode.LIBRARY_LOAD_FAILED,
                "Native library could not be loaded; AliasNull native bootstrap not attempted.",
            )
        }
        val code = try {
            nativeInitializeRuntime(runtimeRootPath)
        } catch (t: Throwable) {
            Log.e(TAG, "JNI handshake failed: native bootstrap threw", t)
            return NativeRuntimeResult.unexpected(t)
        }
        if (code != 0) {
            Log.e(TAG, "JNI handshake failed: native returned code $code")
            return NativeRuntimeResult.failure(nativeCode(code), nativeMessage(code))
        }
        return try {
            val version = nativeRuntimeVersion()
            val bootstrap = nativeBootstrapVersion()
            val caps = nativeCapabilities()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            Log.i(TAG, "JNI handshake successful (runtime $version, bootstrap $bootstrap)")
            NativeRuntimeResult(
                success = true,
                code = NativeBootstrapCode.OK,
                message = "Native runtime bootstrap initialized.",
                runtimeVersion = version,
                bootstrapVersion = bootstrap,
                capabilities = caps,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Native bootstrap succeeded but metadata could not be read", t)
            NativeRuntimeResult(
                success = true,
                code = NativeBootstrapCode.OK,
                message = "Native runtime bootstrap initialized (metadata unavailable).",
            )
        }
    }

    /** Releases the native bootstrap state. No-op unless the library is loaded. */
    fun shutdownNativeRuntime() {
        if (!libraryLoaded) return
        try {
            nativeShutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "Native shutdown failed", t)
        }
    }

    // ---- JNI entry points (implemented in aliasnull_runtime.cpp) ----

    private external fun nativeInitializeRuntime(runtimeRootPath: String): Int

    private external fun nativeShutdown()

    private external fun nativeRuntimeVersion(): String

    private external fun nativeBootstrapVersion(): String

    private external fun nativeCapabilities(): String

    // ---- Error mapping (mirrors the result constants in aliasnull_runtime.cpp) ----

    private fun nativeCode(code: Int): NativeBootstrapCode = when (code) {
        -1 -> NativeBootstrapCode.RUNTIME_ROOT_INVALID
        -2 -> NativeBootstrapCode.RUNTIME_ROOT_NOT_DIRECTORY
        -3 -> NativeBootstrapCode.RUNTIME_DIRECTORY_MISSING
        -4 -> NativeBootstrapCode.RUNTIME_DIRECTORY_NOT_WRITABLE
        -5 -> NativeBootstrapCode.NATIVE_ALREADY_SHUTDOWN
        else -> NativeBootstrapCode.NATIVE_INIT_FAILED
    }

    private fun nativeMessage(code: Int): String = when (code) {
        -1 -> "Native bootstrap rejected the runtime root path."
        -2 -> "Native bootstrap found the runtime root is not a directory."
        -3 -> "Native bootstrap found a required runtime directory is missing."
        -4 -> "Native bootstrap found a required runtime directory is not writable."
        -5 -> "Native bootstrap was requested after the native layer shut down."
        else -> "Native bootstrap failed with unknown native error $code."
    }
}
