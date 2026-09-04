package app.aliasnull.shell.runtime

import android.app.Application
import android.util.Log
import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionRouter
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.AnShellCoreBridge
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeState
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeStatus
import app.aliasnull.shell.runtime.native.AnShellCoreCommandExecutor
import app.aliasnull.shell.runtime.native.AnShellCoreExecutionResult
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
 * Command execution resolves through the execution routing layer, which today
 * selects exactly one genuinely executable backend: the AN Shell core
 * ([ExecutionBackend.AN_SHELL_CORE]), which sends one command string through the
 * packaged Rust language core. That backend executes only while its bridge is
 * READY; when it is not ready no backend executes and no fallback ever runs. The
 * C++ native backend is a future seam and never executes a command.
 *
 * Shell readiness is one derived gate ([shellBackendState]): READY is published
 * only after a real attempt verifies the AN Shell core bridge and that
 * verification reports READY; FAILED only after such an attempt completes
 * without a READY core; INITIALIZING while an attempt runs or none has finished.
 * The gate is published by this manager at real lifecycle points and is never
 * manufactured by a timer or the UI. Native bootstrap success only moves [state]
 * to [ShellRuntimeState.NativeBootstrapReady] - it is a separate (C++)
 * foundation axis that never executes commands and does not by itself move the
 * Shell gate; a bootstrap failure only moves [state] to [ShellRuntimeState.Error]
 * and never stops the independent AN Shell core gate from being verified.
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
 * session engine boundary ([terminalSessionEngine]); it hosts the contract-only
 * foundation, so the boundary is always queryable and reports that no
 * interactive session backend exists. The engine is a sibling of command
 * execution, never a replacement for it, and is released in [shutdown].
 * Above the engine the manager also owns the terminal-session orchestration
 * boundary ([terminalSessionOrchestrator]): the single place a future UI session
 * owner requests an engine session. It is hosted by the contract-only foundation
 * too, so such a request honestly reports that no session backend exists and no
 * engine session is attached. The orchestrator holds no live resources and is not
 * part of shutdown.
 */
class AliasNullRuntimeManager(application: Application) : ShellRuntimeManager {

    private val nativeRuntime: AliasNullNativeRuntime = AliasNullNativeRuntime(application)

    /**
     * The AN Shell core executor: the genuinely executable backend that sends one
     * command string through the packaged Rust language core whenever its bridge
     * is READY. It calls only the [AnShellCoreBridge] facade, never JNI directly.
     */
    private val anShellCoreExecutor: ShellCommandExecutor = AnShellCoreCommandExecutor()

    /**
     * The execution routing layer: the single decision point that resolves each
     * execution request to a genuinely executable backend. AUTO selects the AN
     * Shell core exactly when its bridge is READY and otherwise selects nothing
     * (a command is never handed to a seam or a fallback). The C++ native backend
     * is never executable and never receives a command. Exposed through
     * [ShellRuntimeManager.executor] so the Shell and ViewModel never see backend
     * selection or JNI.
     */
    private val executionRouter: ExecutionRouter by lazy {
        ExecutionRouter(
            executableBackends = mapOf(
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

    /** The Shell gate (Part 27-M): derived READY/FAILED from real bridge verification. */
    private val _shellBackendState = MutableStateFlow(ShellBackendState.INITIALIZING)
    override val shellBackendState: StateFlow<ShellBackendState> = _shellBackendState.asStateFlow()

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
    // libaliasnull_an_shell_core.so that runs after each bootstrap attempt. The
    // check loads the core, verifies its API version and sends a fixed set of
    // canned commands through the full native pipeline. It never routes a user
    // command to the core itself. The READY status this check establishes is the
    // status [backendAvailability] reports for the AN Shell core backend, so a
    // successful check is what lets the AUTO policy execute commands, and it is
    // the single authoritative readiness fact the Shell gate derives READY from.

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
        // One line at construction: the routing decision the Shell will use. The
        // core bridge has not been verified yet, so AUTO resolves to no
        // executable backend here (BACKEND_SELECTION_FAILED); once initialize()
        // verifies the AN Shell core it becomes READY and AUTO selects it. The
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
            // One attempt at a time, and never a re-verification once the AN
            // Shell core is genuinely READY. The gate stays INITIALIZING while an
            // attempt runs or none has completed; a finished attempt publishes
            // its own READY/FAILED value.
            if (bootstrapJob?.isActive == true) return
            if (_shellBackendState.value.phase == ShellBackendPhase.READY) return
            launchInitializationAttempt()
        }
    }

    /**
     * Re-runs the real initialization/verification lifecycle after a FAILED gate.
     * READY can only ever be re-established through genuine bridge verification,
     * never by a timer or a manufactured value. No-op while an attempt is running
     * or once the backend is already READY.
     */
    override fun retryInitialize() {
        synchronized(this) {
            if (bootstrapJob?.isActive == true) return
            if (_shellBackendState.value.phase != ShellBackendPhase.FAILED) return
            launchInitializationAttempt()
        }
    }

    /** Starts one genuine initialization/verification attempt on the background scope. */
    private fun launchInitializationAttempt() {
        _shellBackendState.value = ShellBackendState.INITIALIZING
        bootstrapJob = scope.launch { runInitializationAttempt() }
    }

    private suspend fun runInitializationAttempt() {
        // Native-foundation (C++) axis: bootstrap only when this attempt is the
        // first one (FrontendOnly) or a prior bootstrap failed (Error). Once the
        // foundation is already bootstrapped (a prior FAILED gate with a READY
        // native bootstrap), a retry leaves it in place - re-running it would not
        // change the gate and would double-reserve a session slot. The AN Shell
        // core gate is independent of this axis and is verified below either way.
        val startState = _state.value
        if (startState != ShellRuntimeState.NativeBootstrapReady &&
            startState != ShellRuntimeState.Ready
        ) {
            _state.value = ShellRuntimeState.Initializing
            val result = runCatching { nativeRuntime.initialize() }
                .getOrElse { NativeRuntimeResult.unexpected(it) }
            nativeBootstrapResult = result
            if (!currentCoroutineContext().isActive) return // cancelled during bootstrap
            _state.value =
                if (result.success) ShellRuntimeState.NativeBootstrapReady else ShellRuntimeState.Error
            if (result.success) {
                Log.i(TAG, "Runtime state -> NativeBootstrapReady (version ${result.runtimeVersion})")
                reserveFoundationSession()
                logNativeExecutionSeam()
            } else {
                Log.e(TAG, "Runtime state -> Error: ${result.code} ${result.message}")
                logNativeExecutionSeam()
            }
        }
        if (!currentCoroutineContext().isActive) return

        // The Shell gate is decided ONLY by genuine bridge verification - the one
        // authoritative readiness path. Never by the bootstrap above.
        verifyAnShellCoreBridge()
        publishShellBackendGate()
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
            // bootstrap state remains. The AN Shell core backend is independent
            // of the C++ runtime's lifecycle, so it is not torn down here; its
            // gate still returns to the truthful pre-verification phase so a
            // later initialize() re-verifies from scratch.
            val current = _state.value
            if (current == ShellRuntimeState.NativeBootstrapReady ||
                current == ShellRuntimeState.Initializing
            ) {
                _state.value = ShellRuntimeState.FrontendOnly
            }
            _shellBackendState.value = ShellBackendState.INITIALIZING
        }
    }

    /**
     * Reserves one native session slot to exercise the Kotlin <-> JNI session
     * lifecycle after a successful bootstrap. Failure is logged but never
     * downgrades the bootstrap state: a session slot is an independent
     * foundation, not the execution runtime. Only ever called once per successful
     * bootstrap (a retry after a FAILED gate does not re-enter the bootstrap
     * branch, so no slot is double-reserved).
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
     * status [backendAvailability] reports for the AN Shell core backend - the
     * single authoritative readiness fact the Shell gate derives READY from; the
     * check itself never routes a user command. A bridge failure is recorded and
     * logged, never thrown.
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
                    AnShellCoreExecutionResult.internalError(
                        "AN Shell core probe threw unexpectedly: ${error.message ?: error::class.simpleName}",
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
            if (result.error != null) {
                summary.append(" error=").append(result.error.userMessage)
            }
            summary.append(']')
        }
        anShellCoreProbeSummary = summary.toString()
        Log.i(TAG, anShellCoreProbeSummary.orEmpty())
    }

    /**
     * Publishes the Shell gate from the single authoritative readiness path:
     * [AnShellCoreBridge.currentStatus] surfaced through [backendAvailability].
     * READY exactly when that availability can execute; otherwise FAILED with a
     * user-safe reason. Called only after a real [verifyAnShellCoreBridge]
     * attempt, so the value always reflects a genuine verification outcome.
     */
    private fun publishShellBackendGate() {
        val availability = backendAvailability(ExecutionBackend.AN_SHELL_CORE)
        _shellBackendState.value =
            if (availability.canExecute) {
                ShellBackendState.READY
            } else {
                ShellBackendState.failed(userSafeBridgeReason(anShellCoreBridgeStatus))
            }
    }

    /**
     * Renders the AN Shell core bridge failure as a short, user-safe reason. The
     * precise diagnostic detail (library path, native API hex, probe summary)
     * stays in the diagnostics/logs; the gate carries the plain explanation the
     * UI can show next to Retry.
     */
    private fun userSafeBridgeReason(status: AnShellCoreBridgeStatus?): String = when (status?.state) {
        AnShellCoreBridgeState.LOAD_FAILED ->
            "The AN Shell core could not be loaded on this device."
        AnShellCoreBridgeState.VERSION_MISMATCH ->
            "The AN Shell core version does not match this build."
        AnShellCoreBridgeState.READY,
        AnShellCoreBridgeState.NOT_ATTEMPTED,
        -> "The AN Shell core did not report ready."
        null -> "The AN Shell core could not be verified."
    }

    /** Renders a probe command compactly for the one-line log summary. */
    private fun displayProbe(probe: String): String =
        if (probe.isEmpty()) "empty" else probe

    override fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability = when (backend) {
        ExecutionBackend.AN_SHELL_CORE -> AnShellCoreExecutionSeam.availability(AnShellCoreBridge.currentStatus())
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = nativeRuntime.isNativeLibraryLoaded,
            nativeBootstrapActive = nativeRuntime.isNativeBootstrapActive,
        )
    }

    /**
     * Part 27-Q controlled native-process self-check. Never attempts execution
     * unless the native runtime is genuinely loaded and bootstrapped; otherwise it
     * returns [NativeProcessTestResult.NotReady] and no child is launched. The
     * authorized request comes from [NativeProcessTestKind.request] (built from the
     * policy's canonical invocations) and runs through [NativeProcessExecutionSeam]
     * on the background dispatcher - this is the only place the diagnostic reaches
     * the real runner, so [NativeExecutionPolicy] stays authoritative.
     */
    override suspend fun runNativeProcessTest(case: NativeProcessTestKind): NativeProcessTestResult {
        if (!nativeRuntime.isNativeLibraryLoaded || !nativeRuntime.isNativeBootstrapActive) {
            return NativeProcessTestResult.NotReady(
                kind = case,
                message = notReadyReason(),
            )
        }
        val execution = NativeProcessExecutionSeam.execute(case.request(), nativeRuntime, Dispatchers.Default)
        val expectedMet = execution is NativeProcessExecutionResult.Executed && case.matches(execution.result)
        return NativeProcessTestResult.Outcome(case, execution, expectedMet)
    }

    /** Plain-language reason the native runtime cannot run a self-check process yet. */
    private fun notReadyReason(): String {
        val loaded = nativeRuntime.isNativeLibraryLoaded
        val bootstrapped = nativeRuntime.isNativeBootstrapActive
        return "The native runtime is not ready (library ${if (loaded) "loaded" else "not loaded"}, " +
            "bootstrap ${if (bootstrapped) "active" else "not active"}); no controlled native process was run."
    }

    private companion object {
        const val TAG = "AliasNullRuntimeManager"

        /** Canned commands sent through the AN Shell core by the observational probe. */
        private val AN_SHELL_CORE_PROBES =
            listOf("", "help", "about", "echo hello world", "clear", "unknowncommand", "\"oops")
    }
}
