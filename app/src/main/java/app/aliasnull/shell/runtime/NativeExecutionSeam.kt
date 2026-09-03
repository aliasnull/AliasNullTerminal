package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionBackendStatus

/**
 * Contract-only seam for the future native runtime execution backend.
 *
 * Part 26-I is architectural preparation. This object computes whether the native
 * backend could run a command, taking two booleans the runtime manager already
 * owns (library loaded? bootstrap active?). It deliberately:
 *
 *   - is NOT a [app.aliasnull.shell.execution.ShellCommandExecutor], so nothing
 *     can route a command through it,
 *   - holds no JNI reference and never touches libaliasnull_runtime.so,
 *   - never calls ProcessBuilder, Runtime.exec, fork/exec, or PTY APIs, and
 *   - never reports a command as running or started.
 *
 * Until a future part implements native execution, a fully bootstrapped runtime
 * still reports [ExecutionBackendStatus.NATIVE_EXECUTION_NOT_IMPLEMENTED].
 */
object NativeExecutionSeam {

    /**
     * Describes the native backend's current ability to execute, in honest
     * terms. When bootstrap is active but execution is not implemented, that is
     * exactly what is reported - never a fabricated RUNNING/STARTED/process.
     */
    fun availability(
        nativeLibraryAvailable: Boolean,
        nativeBootstrapActive: Boolean,
    ): ExecutionBackendAvailability = when {
        !nativeLibraryAvailable -> ExecutionBackendAvailability(
            backend = ExecutionBackend.NATIVE_RUNTIME,
            status = ExecutionBackendStatus.NATIVE_LIBRARY_UNAVAILABLE,
            message = "libaliasnull_runtime.so is not loaded; the native execution backend is unavailable.",
        )
        !nativeBootstrapActive -> ExecutionBackendAvailability(
            backend = ExecutionBackend.NATIVE_RUNTIME,
            status = ExecutionBackendStatus.NATIVE_RUNTIME_NOT_READY,
            message = "The native library is loaded but the native runtime is not bootstrapped; no native backend is ready.",
        )
        else -> ExecutionBackendAvailability(
            backend = ExecutionBackend.NATIVE_RUNTIME,
            status = ExecutionBackendStatus.NATIVE_EXECUTION_NOT_IMPLEMENTED,
            message = "Native execution is not implemented yet; this seam is contract-only and never executes commands.",
        )
    }
}
