package app.aliasnull.shell.runtime

import android.app.Application
import android.util.Log
import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionRouter
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.AnShellCoreBridge
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeState
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeStatus
import app.aliasnull.shell.runtime.native.AnShellCoreCommandExecutor
import app.aliasnull.shell.runtime.native.AnShellCoreExecutionResult
import app.aliasnull.shell.runtime.native.AnShellCoreResultKind
import app.aliasnull.shell.runtime.native.NativeRuntimeResult
import app.aliasnull.shell.runtime.native.NativeSessionOutcome
import app.aliasnull.shell.runtime.native.NativeSessionResult
import app.aliasnull.shell.terminal.TerminalSessionEngine
import app.aliasnull.shell.terminal.TerminalSessionEngineFoundation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The app's [ShellRuntimeManager]: owns the honest runtime lifecycle and drives
 * the native bootstrap foundation behind [AliasNullNativeRuntime].
 *
 * Command execution resolves through the execution routing layer: once the AN
 * Shell core bridge is READY, the AUTO route prefers the AN Shell core executor
 * (a genuinely executable backend over the Rust language core); until then - and
 * whenever that bridge is not ready - every command goes to the temporary
 * frontend executor, which is never removed and remains the guaranteed fallback.
 * Native bootstrap success only moves [state]
 * to [ShellRuntimeState.NativeBootstrapReady] - it does not make the C++ runtime
 * execute commands, and a bootstrap failure only moves it to [ShellRuntimeState.Error]
 * while the Shell keeps working through the frontend executor.
 *
 * On a successful bootstrap the manager also reserves one native session slot
 * (see [AliasNullNativeRuntime.createFoundationSession]) so the Kotlin <-> JNI
 * session lifecycle is exercised honestly and observably. The slot is a
 * placeholder identity - READY, never running - and is deterministically closed
 * in [shutdown]. A session failure never downgrades the bootstrap state.
 *
 * The manager holds the [Application] context, not an Activity context, so it
 * never leaks a UI Context. Initialization runs on a background dispatcher and
 * is never triggered from Application.onCreate; the Shell ViewModel calls
 * [initialize] when the Shell runtime is first needed, after which the state
 * survives ordinary UI recomposition.
 *
 * Besides the [executor] command surface the manager owns a read-only terminal
 * session engine boundary ([terminalSessionEngine]); like the frontend manager it
 * hosts the contract-only foundation, so the boundary is always queryable and
 * reports that no interactive session backend exists. The engine is a sibling of
 * command execution, never a replacement for it, and is released in [shutdown].
 * Above the engine the manager also owns the terminal-session orchestration
 * boundary ([terminalSessionOrchestrator]): the single place a future UI session
 * owner requests an engine session. It is hosted by the contract-only foundation
 * too, so such a request honestly reports that no session backend exists and no
 * engine session is attached. The orchestrator holds no live resources and is not
 * part of shutdown.
 */
class AliasNullRuntimeManager(application: Application) : ShellRuntimeManager {

    private val nativeRuntime: AliasNullNativeRuntime = AliasNullNativeRuntime(application)

    /** The genuinely executable temporary backend - the guaranteed AUTO fallback. */
    private val temporaryExecutor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    /**
     * The AN Shell core executor: a genuinely executable backend that sends one
     * command string through the packaged Rust language core whenever its bridge
     * is READY. It calls only the [AnShellCoreBridge] facade, never JNI directly.
     */
    private val anShellCoreExecutor: ShellCommandExecutor = AnShellCoreCommandExecutor()

    /**
     * The execution routing layer: the single decision point that resolves each
     * execution request to a genuinely executable backend. Once the AN Shell core
     * bridge is READY the AUTO policy selects [anShellCoreExecutor]; otherwise it
     * selects [temporaryExecutor]. The C++ native backend is never executable and
     * never receives a command. Exposed through [ShellRuntimeManager.executor] so
     * the Shell and ViewModel never see backend selection or JNI.
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
     * The terminal session engine boundary owned by this runtime. Hosts the same
     * contract-only foundation as the frontend manager, so the boundary is always
     * queryable and honestly reports contract-present / no session backend.
     */
    override val terminalSessionEngine: TerminalSessionEngine = TerminalSessionEngineFoundation

    /**
     * The terminal-session orchestration boundary owned by this runtime. Hosts the
     * contract-only foundation, so a coordination request always honestly reports
     * that no session backend exists and never attaches an engine session.
     */
    override val terminalSessionOrchestrator: TerminalSessionOrchestrator = TerminalSessionOrchestratorFoundation

    private val _state = MutableStateFlow(ShellRuntimeState.FrontendOnly)
    override val state: StateFlow<ShellRuntimeState> = _state.asStateFlow()

    /** Outcome of the most recent bootstrap attempt; null until one completes. */
    @Volatile
    var nativeBootstrapResult: NativeRuntimeResult? = null
        private set

    /**
     * Outcome of the most recent native session-slot operation; null until one
     * completes. A session slot is a placeholder (READY) and never a process.
     */
    @Volatile
    var nativeSessionResult: NativeSessionResult? = null
        private set

    // ---- Observational AN Shell core bridge check (Part 27-G) ----
    //
    // These fields record a diagnostics-only verification of the packaged
    // libaliasnull_an_shell_core.so that runs once after each bootstrap attempt.
    // The check loads the core, verifies its API version and sends a fixed set of
    // canned commands through the full native pipeline. It never routes a user
    // command to the core itself. The READY status this check establishes is the
    // status [backendAvailability] reports for the AN Shell core backend, so a
    // successful check is what lets the AUTO policy prefer that backend for later
    // commands.

    /** Status of the most recent AN Shell core bridge check; null until it runs. */
    @Volatile
    var anShellCoreBridgeStatus: AnShellCoreBridgeStatus? = null
        private set

    /** Outcomes of the canned AN Shell core probe commands; empty until the check runs. */
    @Volatile
    var anShellCoreProbeResults: List<AnShellCoreExecutionResult> = emptyList()
        private set

    /** One-line human summary of the most recent AN Shell core probe. */
    @Volatile
    var anShellCoreProbeSummary: String? = null
        private set

    @Volatile
    private var activeNativeSessionId: Long = NativeSessionResult.NO_SESSION

    // Process/runtime-scoped: the manager outlives any single screen. A bare
    // scope is appropriate here because bootstrap is finite and quick; it is not
    // tied to a composable lifetime.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bootstrapJob: Job? = null

    init {
        // One line at construction: the routing decision the Shell will use.
        // The core bridge has not been verified yet, so AUTO resolves to the
        // temporary backend here; once initialize() verifies the AN Shell core it
        // becomes READY and AUTO prefers that backend for later commands. The
        // route's selected backend is always the actual one.
        val route = executionRouter.resolveAuto()
        Log.i(
            TAG,
            "Execution routing ready: requested=${route.requestedBackend ?: "AUTO"} selected=${route.backend} (${route.status}) - ${route.message}",
        )
        // One line at construction: the honest terminal engine boundary the Shell
        // can always query. The contract-only foundation never hosts a session.
        val engine = terminalSessionEngine.availability
        Log.i(
            TAG,
            "Terminal session engine boundary: contractPresent=${engine.contractPresent} " +
                "sessionBackendAvailable=${engine.sessionBackendAvailable} " +
                "canHostTerminalSession=${engine.canHostTerminalSession}",
        )
    }

    override fun initialize() {
        synchronized(this) {
            val current = _state.value
            if (current == ShellRuntimeState.Initializing ||
                current == ShellRuntimeState.NativeBootstrapReady ||
                current == ShellRuntimeState.Ready ||
                current == ShellRuntimeState.Stopped
            ) {
                return
            }
            if (bootstrapJob?.isActive == true) return

            _state.value = ShellRuntimeState.Initializing
            bootstrapJob = scope.launch {
                val result = runCatching { nativeRuntime.initialize() }
                    .getOrElse { NativeRuntimeResult.unexpected(it) }
                nativeBootstrapResult = result
                if (!currentCoroutineContext().isActive) return@launch // cancelled during bootstrap
                _state.value =
                    if (result.success) ShellRuntimeState.NativeBootstrapReady else ShellRuntimeState.Error
                verifyAnShellCoreBridge()
                if (result.success) {
                    Log.i(TAG, "Runtime state -> NativeBootstrapReady (version ${result.runtimeVersion})")
                    reserveFoundationSession()
                    logNativeExecutionSeam()
                } else {
                    Log.e(TAG, "Runtime state -> Error: ${result.code} ${result.message}")
                    logNativeExecutionSeam()
                }
            }
        }
    }

    override fun shutdown() {
        synchronized(this) {
            bootstrapJob?.cancel()
            bootstrapJob = null
            releaseFoundationSession()
            // Release the owned terminal engine boundary. The contract-only
            // foundation has no live sessions, so this is a deterministic no-op,
            // safe when repeated and safe before any successful initialization.
            terminalSessionEngine.shutdown()
            runCatching { nativeRuntime.shutdown() }
                .onFailure { Log.w(TAG, "Native shutdown reported a problem", it) }
            // With the native (C++) bootstrap released, no native session or
            // bootstrap state remains; the frontend executor stays available and
            // the AN Shell core backend is untouched (it is independent of the
            // C++ runtime's lifecycle).
            if (_state.value == ShellRuntimeState.NativeBootstrapReady) {
                _state.value = ShellRuntimeState.FrontendOnly
            }
        }
    }

    /**
     * Reserves one native session slot to exercise the Kotlin <-> JNI session
     * lifecycle after a successful bootstrap. Failure is logged but never
     * downgrades the bootstrap state: a session slot is an independent
     * foundation, not the execution runtime.
     */
    private fun reserveFoundationSession() {
        val session = runCatching { nativeRuntime.createFoundationSession() }
            .getOrElse { NativeSessionResult.unexpected(it) }
        nativeSessionResult = session
        if (session.outcome == NativeSessionOutcome.SESSION_READY) {
            activeNativeSessionId = session.sessionId
            Log.i(TAG, "Foundation session ready (id=${session.sessionId}, live=${nativeRuntime.liveFoundationSessionCount})")
        } else {
            Log.w(TAG, "Foundation session not reserved: ${session.outcome} ${session.message}")
        }
    }

    /**
     * Closes the reserved session slot deterministically. Closing an
     * unknown/never-created/NO_SESSION id is a benign no-op, so this is safe to
     * call repeatedly and before the native bootstrap is released.
     */
    private fun releaseFoundationSession() {
        val closed = runCatching { nativeRuntime.closeFoundationSession(activeNativeSessionId) }
            .getOrElse { NativeSessionResult.unexpected(it) }
        nativeSessionResult = closed
        activeNativeSessionId = NativeSessionResult.NO_SESSION
        if (closed.outcome == NativeSessionOutcome.SESSION_CLOSED) {
            Log.i(TAG, "Foundation session released (live=${nativeRuntime.liveFoundationSessionCount})")
        } else {
            Log.w(TAG, "Foundation session release reported ${closed.outcome}: ${closed.message}")
        }
    }

    /**
     * Logs the honest state of the future native execution backend exactly once
     * per bootstrap outcome. Even after a successful bootstrap this reports that
     * native execution is NOT implemented, never a fabricated running backend.
     */
    private fun logNativeExecutionSeam() {
        val availability = NativeExecutionSeam.availability(
            nativeLibraryAvailable = nativeRuntime.isNativeLibraryLoaded,
            nativeBootstrapActive = nativeRuntime.isNativeBootstrapActive,
        )
        Log.i(TAG, "Native execution backend: ${availability.status} - ${availability.message}")
    }

    /**
     * Runs the observational AN Shell core bridge check: verifies the packaged
     * libaliasnull_an_shell_core.so handshake and, when ready, sends a fixed set
     * of canned commands through the full native language pipeline, recording
     * each outcome. Diagnostic by intent: the results are exposed as read-only
     * properties and logged. The READY status this check establishes is the same
     * status [backendAvailability] reports for the AN Shell core backend, so a
     * successful check is what lets the AUTO policy prefer that backend for later
     * commands; the check itself never routes a user command. A bridge failure is
     * recorded and logged, never thrown.
     */
    private fun verifyAnShellCoreBridge() {
        val bridgeStatus = runCatching { AnShellCoreBridge.verify() }
            .getOrElse { error ->
                AnShellCoreBridgeStatus(
                    state = AnShellCoreBridgeState.LOAD_FAILED,
                    message = "AN Shell core bridge verification failed: ${error.message ?: error::class.simpleName}",
                )
            }
        anShellCoreBridgeStatus = bridgeStatus
        if (!bridgeStatus.canExecute) {
            anShellCoreProbeSummary =
                "AN Shell core bridge not ready: ${bridgeStatus.state} - ${bridgeStatus.message}"
            Log.w(TAG, anShellCoreProbeSummary.orEmpty())
            return
        }
        val probeResults = AN_SHELL_CORE_PROBES.map { probe ->
            runCatching { AnShellCoreBridge.execute(probe) }
                .getOrElse { error ->
                    AnShellCoreExecutionResult.pipelineError(
                        kind = AnShellCoreResultKind.INTERNAL_ERROR,
                        message = "AN Shell core probe threw unexpectedly: ${error.message ?: error::class.simpleName}",
                    )
                }
        }
        anShellCoreProbeResults = probeResults
        val succeeded = probeResults.count { it.success }
        val summary = StringBuilder(
            "AN Shell core probe: bridge READY, $succeeded/${probeResults.size} probes succeeded. " +
                bridgeStatus.message,
        )
        for ((probe, result) in AN_SHELL_CORE_PROBES.zip(probeResults)) {
            summary.append(" [").append(displayProbe(probe)).append(" -> ")
                .append(result.kind).append(" outputs=").append(result.output.size)
            if (result.errorMessage != null) {
                summary.append(" error=").append(result.errorMessage)
            }
            summary.append(']')
        }
        anShellCoreProbeSummary = summary.toString()
        Log.i(TAG, anShellCoreProbeSummary.orEmpty())
    }

    /** Renders a probe command compactly for the one-line log summary. */
    private fun displayProbe(probe: String): String =
        if (probe.isEmpty()) "empty" else probe

    override fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability = when (backend) {
        ExecutionBackend.TEMPORARY -> ExecutionBackendAvailability.temporary()
        ExecutionBackend.AN_SHELL_CORE -> AnShellCoreExecutionSeam.availability(AnShellCoreBridge.currentStatus())
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = nativeRuntime.isNativeLibraryLoaded,
            nativeBootstrapActive = nativeRuntime.isNativeBootstrapActive,
        )
    }

    private companion object {
        const val TAG = "AliasNullRuntimeManager"

        /** Canned commands sent through the AN Shell core by the observational probe. */
        private val AN_SHELL_CORE_PROBES =
            listOf("", "help", "about", "echo hello world", "clear", "unknowncommand", "\"oops")
    }
}
