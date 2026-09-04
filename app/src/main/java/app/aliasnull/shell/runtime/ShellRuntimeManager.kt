package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionRouter
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import app.aliasnull.shell.runtime.native.AnShellCoreBridge
import app.aliasnull.shell.runtime.native.AnShellCoreCommandExecutor
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
 *       ├── state                       (honest [ShellRuntimeState] lifecycle)
 *       ├── executor                    (resolved through the execution routing layer;
 *       │                                prefers the AN Shell core backend when its bridge
 *       │                                is READY, else the temporary frontend executor)
 *       ├── terminalSessionEngine       (the read-only terminal session engine boundary;
 *       │                                hosted by the contract-only foundation)
 *       ├── terminalSessionOrchestrator (the single UI-session <-> engine-session
 *       │                                coordination boundary, hosted by the
 *       │                                contract-only foundation)
 *       └── native bootstrap            (loaded and initialized behind the scenes when
 *                                        the concrete manager has a native layer)
 *
 * Command execution ([executor]) and the terminal session engine
 * ([terminalSessionEngine]) are separate, never merged concerns: the engine is
 * not a [ShellCommandExecutor] and never receives a command from the routing
 * layer.
 *
 * [initialize] brings up whichever runtime foundation the concrete manager has
 * (the frontend-only manager has none and stays on [ShellRuntimeState.FrontendOnly]).
 * Initializing the C++ native bootstrap must never be conflated with command
 * execution becoming native: routing moves to the native runtime backend only in
 * a future phase when that backend exists and can genuinely run. The AN Shell
 * core backend (Rust) is a separate, real command backend that the concrete
 * managers wire in once its bridge is READY.
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
     * The terminal-session orchestration boundary: the single coordination seam
     * between a Shell UI session and [terminalSessionEngine] (Part 26-N). A future
     * UI session owner asks this boundary - not the engine directly - for an
     * engine session and, later, to release one. Hosted today by the contract-only
     * foundation, which honestly reports that no session backend exists, so no
     * engine session is ever attached. It is not a command executor and never
     * participates in command routing; it also owns no live resources, so runtime
     * shutdown does not call into it.
     */
    val terminalSessionOrchestrator: TerminalSessionOrchestrator

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
     * always [ExecutionBackendStatus.ACTIVE]; the AN Shell core backend reports
     * ACTIVE only while its bridge is READY and otherwise exactly why it is not
     * ready; the native backend reports why it cannot run (library unavailable /
     * not bootstrapped / not implemented). This is the runtime boundary's own
     * answer - no JNI leaks to callers.
     */
    fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability
}

/**
 * Pure frontend manager: no native bootstrap is attached, so the state stays
 * [ShellRuntimeState.FrontendOnly] and AUTO resolves to the temporary executor.
 * The AN Shell core backend is registered (so the routing layer can describe it)
 * but is never ACTIVE under this manager, because nothing here verifies or loads
 * the core bridge; its status therefore stays NOT_ATTEMPTED and AUTO always falls
 * through to the temporary executor. Kept as the minimal, always-correct fallback;
 * the app normally uses [AliasNullRuntimeManager], which additionally drives native
 * bootstrap and verifies the AN Shell core.
 */
class FrontendShellRuntimeManager : ShellRuntimeManager {
    override val state: StateFlow<ShellRuntimeState> =
        MutableStateFlow(ShellRuntimeState.FrontendOnly).asStateFlow()

    /** The genuinely executable temporary backend - always ACTIVE under this manager. */
    private val temporaryExecutor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    /**
     * The AN Shell core backend. Registered so the routing layer can describe it,
     * but never ACTIVE here: nothing in this manager verifies (loads) the core
     * bridge, so its status stays NOT_ATTEMPTED and AUTO keeps resolving to the
     * temporary executor.
     */
    private val anShellCoreExecutor: ShellCommandExecutor = AnShellCoreCommandExecutor()

    /**
     * Execution routing layer; AUTO resolves to the temporary executor because
     * this manager never makes the AN Shell core bridge READY.
     */
    private val executionRouter: ExecutionRouter by lazy {
        ExecutionRouter(
            executableBackends = mapOf(
                ExecutionBackend.TEMPORARY to temporaryExecutor,
                ExecutionBackend.AN_SHELL_CORE to anShellCoreExecutor,
            ),
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

    /**
     * The terminal-session orchestration boundary. The frontend manager hosts the
     * same contract-only foundation as the native manager, so a coordination
     * request always honestly reports that no session backend exists and never
     * attaches an engine session.
     */
    override val terminalSessionOrchestrator: TerminalSessionOrchestrator = TerminalSessionOrchestratorFoundation

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
        ExecutionBackend.AN_SHELL_CORE -> AnShellCoreExecutionSeam.availability(AnShellCoreBridge.currentStatus())
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = false,
            nativeBootstrapActive = false,
        )
    }
}
