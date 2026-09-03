package app.aliasnull.shell.runtime.native

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Application/runtime-scoped owner of the AliasNull native runtime bootstrap.
 *
 * This is deliberately NOT a Linux runtime, a shell, or a command backend. It
 * prepares the application-private storage that a future runtime will own and
 * drives the native bootstrap foundation behind [NativeRuntimeBridge]. Command
 * execution continues to live in the temporary frontend executor and is not
 * affected by this class.
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
     * implies a broken command executor - the temporary frontend executor is
     * independent of this layer.
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
