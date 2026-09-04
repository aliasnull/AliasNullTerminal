package app.aliasnull.shell.execution

/**
 * Stable identity of a command execution backend, exposed behind the single
 * [ShellCommandExecutor] contract.
 *
 * Exactly one backend is the active executor at a time, and this list is kept
 * small and honest:
 *
 *   [TEMPORARY]      - the in-process frontend executor. Always
 *                      [ExecutionBackendStatus.ACTIVE] and the guaranteed
 *                      fallback of the AUTO policy.
 *   [AN_SHELL_CORE]  - the AN Shell core backend: executes one command string
 *                      through the packaged Rust language core (lex -> parse ->
 *                      analyze -> execute_builtin). Genuinely executable only
 *                      while the libaliasnull_an_shell_core.so bridge is READY.
 *   [NATIVE_RUNTIME] - the future AliasNull native runtime backend (process/PTY).
 *                      It exists only as a contract/seam for now; it can never
 *                      execute a command in this milestone.
 *
 * UI code never needs this to know which native/JNI technology is involved; the
 * runtime boundary ([app.aliasnull.shell.runtime.ShellRuntimeManager]) answers
 * backend questions in these terms.
 */
enum class ExecutionBackend {
    TEMPORARY,
    AN_SHELL_CORE,
    NATIVE_RUNTIME,
}

/**
 * Honest answer to "can this backend execute a command right now?". Each value
 * keeps one reason distinct so a caller and the logs can tell WHY a backend is
 * not executing instead of collapsing every case into a generic "unavailable".
 */
enum class ExecutionBackendStatus {
    /** The backend is present and is the active command executor. */
    ACTIVE,

    /**
     * libaliasnull_an_shell_core.so bridge is not ready, so the AN Shell core
     * backend cannot execute: the bridge has not been verified yet, the library
     * could not be loaded, or the native API version did not match this build.
     */
    AN_SHELL_CORE_UNAVAILABLE,

    /** libaliasnull_runtime.so is not loaded, so the native backend cannot exist. */
    NATIVE_LIBRARY_UNAVAILABLE,

    /** The library is loaded but the native runtime is not bootstrapped/ready. */
    NATIVE_RUNTIME_NOT_READY,

    /** The native backend exists only as a seam; execution is not implemented. */
    NATIVE_EXECUTION_NOT_IMPLEMENTED,

    /** No backend could be selected (a selection-contract edge case). */
    BACKEND_SELECTION_FAILED,
}

/**
 * Availability of one [ExecutionBackend] right now, with a user-safe [message].
 *
 * [canExecute] is true only for an [ExecutionBackendStatus.ACTIVE] backend. No
 * backend is ever reported executable merely because a seam or contract exists.
 */
data class ExecutionBackendAvailability(
    val backend: ExecutionBackend,
    val status: ExecutionBackendStatus,
    val message: String,
) {
    val canExecute: Boolean
        get() = status == ExecutionBackendStatus.ACTIVE

    companion object {
        /** Availability of the temporary frontend backend, which is always active. */
        fun temporary(): ExecutionBackendAvailability = ExecutionBackendAvailability(
            backend = ExecutionBackend.TEMPORARY,
            status = ExecutionBackendStatus.ACTIVE,
            message = "Temporary frontend executor is the active command execution backend.",
        )
    }
}
