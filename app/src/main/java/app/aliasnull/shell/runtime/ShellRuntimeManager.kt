package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the runtime lifecycle and the [ShellCommandExecutor] the Shell should use.
 *
 * The manager is the Shell-facing abstraction over everything below it:
 *
 *   ShellRuntimeManager
 *       ├── state            (honest [ShellRuntimeState] lifecycle)
 *       ├── executor         (currently the temporary frontend executor)
 *       └── native bootstrap (loaded and initialized behind the scenes when the
 *                             concrete manager has a native layer)
 *
 * [initialize] brings up whichever runtime foundation the concrete manager has
 * (the frontend-only manager has none and stays on [ShellRuntimeState.FrontendOnly]).
 * Initializing the native bootstrap must never be conflated with command
 * execution becoming native: the executor is swapped only in a future phase when
 * a real AliasNull execution backend exists.
 */
interface ShellRuntimeManager {

    /** Current availability of the AliasNull runtime. */
    val state: StateFlow<ShellRuntimeState>

    /** The executor that should run commands right now. */
    val executor: ShellCommandExecutor

    /**
     * Begins asynchronous bootstrap of the runtime foundation. Safe to call
     * repeatedly; no-op once already initializing, bootstrapped or stopped.
     */
    fun initialize()

    /** Releases the runtime foundation. No-op for a frontend-only manager. */
    fun shutdown()

    /**
     * Describes whether a specific [ExecutionBackend] can execute commands right
     * now, in honest [ExecutionBackendStatus] terms. The temporary backend is
     * always [ExecutionBackendStatus.ACTIVE]; the native backend reports exactly
     * why it cannot run (library unavailable / not bootstrapped / not
     * implemented). This is the runtime boundary's own answer - no JNI leaks to
     * callers.
     */
    fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability
}

/**
 * Pure frontend manager: no native layer is attached, so the state stays
 * [ShellRuntimeState.FrontendOnly] and only the temporary executor responds.
 * Kept as the minimal, always-correct fallback; the app normally uses
 * [AliasNullRuntimeManager], which additionally drives native bootstrap.
 */
class FrontendShellRuntimeManager : ShellRuntimeManager {
    override val state: StateFlow<ShellRuntimeState> =
        MutableStateFlow(ShellRuntimeState.FrontendOnly).asStateFlow()

    override val executor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    override fun initialize() {
        // Frontend-only: there is nothing to bootstrap.
    }

    override fun shutdown() {
        // Frontend-only: there is nothing to release.
    }

    override fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability = when (backend) {
        ExecutionBackend.TEMPORARY -> ExecutionBackendAvailability.temporary()
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = false,
            nativeBootstrapActive = false,
        )
    }
}
