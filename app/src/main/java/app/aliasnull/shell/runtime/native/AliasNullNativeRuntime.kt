package app.aliasnull.shell.runtime.native

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Application/runtime-scoped owner of the AliasNull native runtime bootstrap and
 * its session-slot foundation.
 *
 * This is deliberately NOT a Linux runtime, a shell, or a command backend. It
 * prepares the application-private storage that a future runtime will own and
 * drives the native bootstrap foundation behind [NativeRuntimeBridge]. Command
 * execution is a separate concern owned by the AN Shell core backend and is not
 * affected by this class. Since Part 27-O it also exposes a real one-shot
 * process-runner capability ([runProcess]) behind the same bridge; like the
 * bootstrap it is independent of AN Shell command routing and is wired to no
 * command.
 *
 * The session-slot methods ([createFoundationSession], [closeFoundationSession],
 * [foundationSessionState], [liveFoundationSessionCount]) operate on opaque
 * native placeholder identities for a future execution backend. A created slot
 * is READY and nothing is forked, spawned or run; it is never a process or PTY.
 *
 * Storage layout (all under the application's noBackupFilesDir so runtime state
 * is disposable, machine-specific and never cloud-backed):
 *
 *   <noBackupFilesDir>/runtime/           runtime bootstrap root (passed to native)
 *       ├── state/                        reserved: future runtime state
 *       ├── tmp/                          reserved: future runtime temporary files
 *       └── metadata/                     reserved: future runtime metadata
 *
 * No /bin, /etc, /usr or fake root filesystem is created here; those belong to a
 * genuine future userspace phase.
 */
class AliasNullNativeRuntime(context: Context) {

    private val appContext = context.applicationContext

    /** The single runtime bootstrap root, under application-private storage. */
    val runtimeRoot: File by lazy { File(appContext.noBackupFilesDir, RUNTIME_ROOT_NAME) }

    /** Runtime-owned bootstrap directories created and validated before native init. */
    private val requiredDirectories: List<File> by lazy {
        listOf(SUBDIR_STATE, SUBDIR_TMP, SUBDIR_METADATA).map { File(runtimeRoot, it) }
    }

    /**
     * Attempts the full bootstrap: load the native library, prepare/validate the
     * runtime directories, then ask the native layer to initialize. Idempotent
     * (the loader and the native layer both guard re-entry) and safe to call more
     * than once. Returns an honest [NativeRuntimeResult]; a failure here never
     * implies a broken Shell - command execution is a separate concern (the AN
     * Shell core backend) that is independent of this layer.
     */
    fun initialize(): NativeRuntimeResult {
        val root = runtimeRoot
        if (!NativeRuntimeBridge.ensureLibraryLoaded()) {
            return NativeRuntimeResult.failure(
                NativeBootstrapCode.LIBRARY_LOAD_FAILED,
                "Native library could not be loaded; AliasNull native bootstrap not attempted.",
            )
        }
        prepareDirectories(root)?.let { return it }
        Log.i(TAG, "Runtime directories ready under ${root.absolutePath}")
        return NativeRuntimeBridge.initializeNativeRuntime(root.absolutePath)
    }

    /** Releases native bootstrap state. Safe to call even if initialization never ran. */
    fun shutdown() {
        NativeRuntimeBridge.shutdownNativeRuntime()
    }

    /** True once libaliasnull_runtime.so has been loaded (a load is never undone). */
    val isNativeLibraryLoaded: Boolean
        get() = NativeRuntimeBridge.isLibraryLoaded

    /** True only while the native bootstrap has succeeded and not yet been released. */
    val isNativeBootstrapActive: Boolean
        get() = NativeRuntimeBridge.isBootstrapActive

    // ---- Session-slot foundation (placeholder identities only; nothing runs) ----

    /** Reserves one native session slot; see [NativeRuntimeBridge.createNativeSession]. */
    fun createFoundationSession(): NativeSessionResult =
        NativeRuntimeBridge.createNativeSession()

    /**
     * Closes a native session slot deterministically (idempotent for unknown,
     * already-closed or never-created ids); see [NativeRuntimeBridge.closeNativeSession].
     */
    fun closeFoundationSession(sessionId: Long): NativeSessionResult =
        NativeRuntimeBridge.closeNativeSession(sessionId)

    /** Lifecycle state of a live session slot, or null when not live/inactive. */
    fun foundationSessionState(sessionId: Long): NativeSessionState? =
        NativeRuntimeBridge.queryNativeSessionState(sessionId)

    /** Number of currently live session slots (0 once all are closed). */
    val liveFoundationSessionCount: Int
        get() = NativeRuntimeBridge.liveNativeSessionCount()

    // ---- Real one-shot process runner (Part 27-O; an internal capability) ----

    /**
     * Runs one real child process ([NativeProcessRequest]) to completion and
     * returns its genuine result ([NativeProcessResult]). This is blocking: it
     * returns only after the child terminates, so it MUST be called from a
     * background thread and never from the Android main/UI thread; a future
     * consumer dispatches it on the existing coroutine infrastructure. It is an
     * internal capability, not a command backend: it is not connected to the
     * execution router, the Shell, or the AN Shell core, and no user command
     * reaches it yet. stdout/stderr are kept separate and the exit status is the
     * child's real one.
     */
    fun runProcess(request: NativeProcessRequest): NativeProcessResult =
        NativeRuntimeBridge.runProcess(request)

    /** Creates any missing runtime directories and validates them; null when all are ready. */
    private fun prepareDirectories(root: File): NativeRuntimeResult? {
        if (!ensureWritableDirectory(root)) {
            return directoryFailure("Could not create the runtime root directory", root)
        }
        for (dir in requiredDirectories) {
            if (!dir.exists() && !dir.mkdirs()) {
                return NativeRuntimeResult.failure(
                    NativeBootstrapCode.RUNTIME_DIRECTORY_MISSING,
                    "Could not create runtime directory: ${dir.absolutePath}",
                )
            }
            if (!ensureWritableDirectory(dir)) {
                return directoryFailure("Runtime directory is not writable", dir)
            }
        }
        return null
    }

    private fun ensureWritableDirectory(dir: File): Boolean =
        (dir.exists() || dir.mkdirs()) && dir.isDirectory && dir.canRead() && dir.canWrite()

    private fun directoryFailure(reason: String, dir: File): NativeRuntimeResult {
        val code = if (dir.exists()) {
            NativeBootstrapCode.RUNTIME_DIRECTORY_NOT_WRITABLE
        } else {
            NativeBootstrapCode.RUNTIME_DIRECTORY_MISSING
        }
        Log.e(TAG, "$reason: ${dir.absolutePath}")
        return NativeRuntimeResult.failure(code, "$reason: ${dir.absolutePath}")
    }

    private companion object {
        const val TAG = "AliasNullNativeRuntime"
        const val RUNTIME_ROOT_NAME = "runtime"
        const val SUBDIR_STATE = "state"
        const val SUBDIR_TMP = "tmp"
        const val SUBDIR_METADATA = "metadata"
    }
}
