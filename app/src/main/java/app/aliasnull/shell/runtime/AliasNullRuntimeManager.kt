package app.aliasnull.shell.runtime

import android.app.Application
import android.util.Log
import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionRouter
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.NativeRuntimeResult
import app.aliasnull.shell.runtime.native.NativeSessionOutcome
import app.aliasnull.shell.runtime.native.NativeSessionResult
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
 * Command execution behavior is deliberately unchanged: [executor] resolves
 * through the execution routing layer, whose AUTO route sends every command to
 * the temporary frontend executor. Native bootstrap success only moves [state]
 * to [ShellRuntimeState.NativeBootstrapReady] - it does not make the executor
 * native, and a bootstrap failure only moves it to [ShellRuntimeState.Error]
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
 */
class AliasNullRuntimeManager(application: Application) : ShellRuntimeManager {

    private val nativeRuntime: AliasNullNativeRuntime = AliasNullNativeRuntime(application)

    /** The genuinely executable temporary backend - the only real executor today. */
    private val temporaryExecutor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    /**
     * The execution routing layer: the single decision point that resolves each
     * execution request to a genuinely executable backend. Today AUTO resolves to
     * [temporaryExecutor]; the native backend is never executable and never
     * receives a command. Exposed through [ShellRuntimeManager.executor] so the
     * Shell and ViewModel never see backend selection or JNI.
     */
    private val executionRouter: ExecutionRouter by lazy {
        ExecutionRouter(
            executableBackends = mapOf(ExecutionBackend.TEMPORARY to temporaryExecutor),
            availabilityOf = ::backendAvailability,
        )
    }

    override val executor: ShellCommandExecutor
        get() = executionRouter

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

    @Volatile
    private var activeNativeSessionId: Long = NativeSessionResult.NO_SESSION

    // Process/runtime-scoped: the manager outlives any single screen. A bare
    // scope is appropriate here because bootstrap is finite and quick; it is not
    // tied to a composable lifetime.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bootstrapJob: Job? = null

    init {
        // One line at construction: the routing decision the Shell will use.
        // AUTO resolves to the temporary backend while native execution is not
        // implemented; the route's selected backend is always the actual one.
        val route = executionRouter.resolveAuto()
        Log.i(
            TAG,
            "Execution routing ready: requested=${route.requestedBackend ?: "AUTO"} selected=${route.backend} (${route.status}) - ${route.message}",
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
            runCatching { nativeRuntime.shutdown() }
                .onFailure { Log.w(TAG, "Native shutdown reported a problem", it) }
            // With the native bootstrap released only the frontend executor remains.
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

    override fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability = when (backend) {
        ExecutionBackend.TEMPORARY -> ExecutionBackendAvailability.temporary()
        ExecutionBackend.NATIVE_RUNTIME -> NativeExecutionSeam.availability(
            nativeLibraryAvailable = nativeRuntime.isNativeLibraryLoaded,
            nativeBootstrapActive = nativeRuntime.isNativeBootstrapActive,
        )
    }

    private companion object {
        const val TAG = "AliasNullRuntimeManager"
    }
}
