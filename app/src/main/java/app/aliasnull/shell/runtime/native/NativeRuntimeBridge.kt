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
 * Since the session foundation (Part 26-H) this object also owns the only JNI
 * entry points that create/inspect/close native session slots. A slot is a
 * placeholder identity for a future execution backend: creating one spawns
 * nothing, and the returned results never claim a process or PTY is running.
 *
 * Since Part 27-O this object also owns the only JNI entry point that runs a
 * real child process ([nativeRunProcess]): a one-shot argv request with genuine
 * stdin/stdout/stderr capture and a real exit status, never a mock. It is not a
 * command backend and is not connected to Shell command routing; it is an
 * internal capability for future consumers and must be called from a background
 * thread (it blocks until the child terminates).
 *
 * The external declarations below map to the mangled symbols implemented in
 * app/src/main/cpp/aliasnull_runtime.cpp (bootstrap/session) and
 * app/src/main/cpp/process_execution_jni.cpp (the process runner); keep names
 * and signatures in sync with those files. This object is a singleton, so its
 * native methods are instance methods (the C++ second parameter is jobject).
 */
internal object NativeRuntimeBridge {

    private const val TAG = "AliasNullNativeBridge"
    private const val LIBRARY_NAME = "aliasnull_runtime"

    @Volatile
    private var libraryLoaded = false

    /** True only while the native bootstrap has succeeded and not yet been released. */
    @Volatile
    private var bootstrapActive = false

    private val lock = Any()

    val isLibraryLoaded: Boolean get() = libraryLoaded

    /** True only while the native bootstrap has succeeded and not yet been released. */
    val isBootstrapActive: Boolean get() = bootstrapActive

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
        bootstrapActive = true
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
        bootstrapActive = false
        if (!libraryLoaded) return
        try {
            nativeShutdown()
        } catch (t: Throwable) {
            Log.e(TAG, "Native shutdown failed", t)
        }
    }

    // ---- Native session-slot foundation ----

    /**
     * Reserves one native session slot and reports the outcome honestly. The
     * caller can distinguish why no slot exists: the library is unavailable,
     * bootstrap never succeeded, or the native layer failed to reserve one.
     * A successful slot is [NativeSessionOutcome.SESSION_READY] with a stable
     * opaque [NativeSessionResult.sessionId]; nothing is running.
     */
    fun createNativeSession(): NativeSessionResult {
        if (!libraryLoaded) {
            return NativeSessionResult(
                NativeSessionOutcome.LIBRARY_UNAVAILABLE,
                message = "Native library could not be loaded; no session slot can be reserved.",
            )
        }
        if (!bootstrapActive) {
            return NativeSessionResult(
                NativeSessionOutcome.BOOTSTRAP_NOT_READY,
                message = "Native bootstrap is not active; no session slot can be reserved.",
            )
        }
        val id = try {
            nativeCreateSession()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeCreateSession threw", t)
            return NativeSessionResult.unexpected(t)
        }
        if (id <= 0L) {
            return NativeSessionResult(
                NativeSessionOutcome.SESSION_PREP_FAILED,
                message = "Native layer could not reserve a session slot.",
            )
        }
        return NativeSessionResult(
            outcome = NativeSessionOutcome.SESSION_READY,
            sessionId = id,
            state = NativeSessionState.READY,
            message = "Native session slot ready (placeholder; nothing is running).",
        )
    }

    /**
     * Closes a session slot deterministically. A [sessionId] of [NO_SESSION]
     * and an unknown/already-closed id are both benign: the slot is closed or
     * was never open, reported as [NativeSessionOutcome.SESSION_CLOSED]. Safe to
     * call repeatedly.
     */
    fun closeNativeSession(sessionId: Long): NativeSessionResult {
        if (sessionId <= NativeSessionResult.NO_SESSION) {
            return NativeSessionResult(
                NativeSessionOutcome.SESSION_CLOSED,
                sessionId = sessionId,
                message = "No live session slot to close (idempotent no-op).",
            )
        }
        if (!bootstrapActive) {
            return NativeSessionResult(
                NativeSessionOutcome.SESSION_LAYER_STOPPED,
                sessionId = sessionId,
                message = "Native bootstrap is not active; nothing was closed.",
            )
        }
        val code = try {
            nativeCloseSession(sessionId)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeCloseSession threw", t)
            return NativeSessionResult.unexpected(t)
        }
        return if (code == 0) {
            NativeSessionResult(
                outcome = NativeSessionOutcome.SESSION_CLOSED,
                sessionId = sessionId,
                state = NativeSessionState.CLOSED,
                message = "Native session slot closed (or already closed).",
            )
        } else {
            NativeSessionResult(
                outcome = NativeSessionOutcome.SESSION_LAYER_STOPPED,
                sessionId = sessionId,
                message = "Native close could not run (code $code).",
            )
        }
    }

    /**
     * Lifecycle state of a live session slot, or null when [sessionId] is not a
     * live session (never created, or already closed). Also null when the native
     * layer is not active. A non-null result never claims a process is running.
     */
    fun queryNativeSessionState(sessionId: Long): NativeSessionState? {
        if (sessionId <= NativeSessionResult.NO_SESSION || !bootstrapActive) return null
        val code = try {
            nativeSessionState(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "nativeSessionState threw", t)
            return null
        }
        return nativeState(code)
    }

    /** Number of currently live session slots; 0 when the native layer is inactive. */
    fun liveNativeSessionCount(): Int {
        if (!libraryLoaded || !bootstrapActive) return 0
        return try {
            nativeActiveSessionCount()
        } catch (t: Throwable) {
            Log.w(TAG, "nativeActiveSessionCount threw", t)
            0
        }
    }

    // ---- Real one-shot process runner (Part 27-O; not connected to command routing) ----

    /**
     * Runs one real child process to completion and returns its genuine result.
     *
     * The request is validated here (empty argv, empty executable, malformed
     * environment key) before the native boundary is touched, so an invalid
     * request returns a structured [NativeProcessOutcome.INTERNAL_ERROR] without
     * a JNI round trip. When the library is not loaded the result is
     * [NativeProcessOutcome.RUNNER_UNAVAILABLE]. The native layer validates the
     * same constraints again, so a defensive failure never reaches the native
     * runner as a malformed array.
     *
     * This is a blocking call that returns only after the child terminates; it
     * MUST run on a background thread, never the Android main/UI thread. It does
     * not require the native bootstrap (runtime-directory foundation) to be
     * active: spawning a process is independent of the bootstrap root.
     */
    fun runProcess(request: NativeProcessRequest): NativeProcessResult {
        request.validationError()?.let { message ->
            return NativeProcessResult.internalError(message)
        }
        if (!libraryLoaded) {
            return NativeProcessResult.runnerUnavailable(
                "libaliasnull_runtime.so could not be loaded; no process can be run.",
            )
        }
        val argv = request.argv.toTypedArray()
        val envOverrides = request.environment
            .takeIf { it.isNotEmpty() }
            ?.map { (key, value) -> "$key=$value" }
            ?.toTypedArray()
        val payload = try {
            nativeRunProcess(argv, envOverrides, request.workingDirectory, request.stdinBytes)
        } catch (t: Throwable) {
            Log.e(TAG, "nativeRunProcess threw", t)
            return NativeProcessResult.internalError(
                "Native process runner error: ${t.message ?: t::class.simpleName ?: "unknown"}",
            )
        }
        if (payload == null) {
            return NativeProcessResult.internalError(
                "The native process runner could not build a result payload.",
            )
        }
        return NativeProcessPayloadCodec.decode(payload)
    }

    // ---- JNI entry points (implemented in aliasnull_runtime.cpp) ----

    private external fun nativeInitializeRuntime(runtimeRootPath: String): Int

    private external fun nativeShutdown()

    private external fun nativeRuntimeVersion(): String

    private external fun nativeBootstrapVersion(): String

    private external fun nativeCapabilities(): String

    private external fun nativeCreateSession(): Long

    private external fun nativeSessionState(sessionId: Long): Int

    private external fun nativeActiveSessionCount(): Int

    private external fun nativeCloseSession(sessionId: Long): Int

    // (implemented in process_execution_jni.cpp on the same Kotlin owner)
    private external fun nativeRunProcess(
        argv: Array<String>,
        envOverrides: Array<String>?,
        workingDirectory: String?,
        stdinBytes: ByteArray?,
    ): ByteArray?

    // ---- State/error mapping (mirrors the constants in aliasnull_runtime.cpp) ----

    private fun nativeState(code: Int): NativeSessionState? = when (code) {
        0 -> NativeSessionState.UNINITIALIZED
        1 -> NativeSessionState.READY
        2 -> NativeSessionState.STARTING
        3 -> NativeSessionState.RUNNING
        4 -> NativeSessionState.CLOSED
        5 -> NativeSessionState.ERROR
        else -> null
    }

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
