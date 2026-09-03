package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionRouter
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import app.aliasnull.shell.terminal.TerminalSessionEngine
import app.aliasnull.shell.terminal.TerminalSessionEngineFoundation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the runtime lifecycle and the [ShellCommandExecutor] the Shell should use.
 *
 * The manager is the Shell-facing abstraction over everything below it:
 *
 *   ShellRuntimeManager
 *       ├── state                 (honest [ShellRuntimeState] lifecycle)
 *       ├── executor              (resolved through the execution routing layer;
 *       │                          currently routes every command to the temporary
 *       │                          frontend executor)
 *       ├── terminalSessionEngine (the read-only terminal session engine boundary;
 *       │                          hosted by the contract-only foundation)
 *       └── native bootstrap      (loaded and initialized behind the scenes when
 *                                  the concrete manager has a native layer)
 *
 * Command execution ([executor]) and the terminal session engine
 * ([terminalSessionEngine]) are separate, never merged concerns: the engine is
 * not a [ShellCommandExecutor] and never receives a command from the routing
 * layer.
 *
 * [initialize] brings up whichever runtime foundation the concrete manager has
 * (the frontend-only manager has none and stays on [ShellRuntimeState.FrontendOnly]).
 * Initializing the native bootstrap must never be conflated with command
 * execution becoming native: routing moves to a native backend only in a future
 * phase when a real AliasNull execution backend exists and can genuinely run.
 */
interface ShellRuntimeManager {

    /** Current availability of the AliasNull runtime. */
    val state: StateFlow<ShellRuntimeState>

    /** The executor commands are routed to right now (the routing layer's AUTO selection). */
    val executor: ShellCommandExecutor

    /**
     * The read-only terminal-session engine boundary owned by this runtime. This
     * is a separate concern from [executor]: [executor] routes batch commands
     * through the execution routing layer, while this engine exposes the terminal
     * session contract. Every manager hosts the engine (currently the contract-only
     * foundation), so the boundary is always queryable and the app can always ask,
     * in honest terms, whether an interactive terminal session backend exists.
     */
    val terminalSessionEngine: TerminalSessionEngine

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

    /** The genuinely executable temporary backend - the only real executor today. */
    private val temporaryExecutor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    /**
     * Execution routing layer; AUTO always resolves to the temporary executor
     * because this manager has no native layer at all.
     */
    private val executionRouter: ExecutionRouter by lazy {
        ExecutionRouter(
            executableBackends = mapOf(ExecutionBackend.TEMPORARY to temporaryExecutor),
            availabilityOf = ::backendAvailability,
        )
    }

    override val executor: ShellCommandExecutor
        get() = executionRouter

    /**
     * The terminal session engine boundary. The frontend manager hosts the same
     * contract-only foundation as the native manager, so the boundary is always
     * queryable and honestly reports contract-present / no session backend.
     */
    override val terminalSessionEngine: TerminalSessionEngine = TerminalSessionEngineFoundation

    override fun initialize() {
        // Frontend-only: there is nothing to bootstrap.
    }

    override fun shutdown() {
        // Frontend-only: release the owned terminal engine boundary. The contract-only
        // foundation has no live sessions, so this is a deterministic no-op.
        terminalSessionEngine.shutdown()
    }

    override fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability = when (backend) {
        ExecutionBackend.TEMPORARY -> ExecutionBackendAvailability.temporary()
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = false,
            nativeBootstrapActive = false,
        )
    }
}
