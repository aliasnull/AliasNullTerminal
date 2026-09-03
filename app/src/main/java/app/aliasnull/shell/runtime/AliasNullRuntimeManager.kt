package app.aliasnull.shell.runtime

import android.app.Application
import android.util.Log
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.NativeRuntimeResult
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
 * Command execution is deliberately unchanged: [executor] remains the temporary
 * frontend executor. Native bootstrap success only moves [state] to
 * [ShellRuntimeState.NativeBootstrapReady] - it does not make the executor
 * native, and a bootstrap failure only moves it to [ShellRuntimeState.Error]
 * while the Shell keeps working through the frontend executor.
 *
 * The manager holds the [Application] context, not an Activity context, so it
 * never leaks a UI Context. Initialization runs on a background dispatcher and
 * is never triggered from Application.onCreate; the Shell ViewModel calls
 * [initialize] when the Shell runtime is first needed, after which the state
 * survives ordinary UI recomposition.
 */
class AliasNullRuntimeManager(application: Application) : ShellRuntimeManager {

    private val nativeRuntime: AliasNullNativeRuntime = AliasNullNativeRuntime(application)

    private val _state = MutableStateFlow(ShellRuntimeState.FrontendOnly)
    override val state: StateFlow<ShellRuntimeState> = _state.asStateFlow()

    override val executor: ShellCommandExecutor = TemporaryShellCommandExecutor()

    /** Outcome of the most recent bootstrap attempt; null until one completes. */
    @Volatile
    var nativeBootstrapResult: NativeRuntimeResult? = null
        private set

    // Process/runtime-scoped: the manager outlives any single screen. A bare
    // scope is appropriate here because bootstrap is finite and quick; it is not
    // tied to a composable lifetime.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bootstrapJob: Job? = null

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
                } else {
                    Log.e(TAG, "Runtime state -> Error: ${result.code} ${result.message}")
                }
            }
        }
    }

    override fun shutdown() {
        synchronized(this) {
            bootstrapJob?.cancel()
            bootstrapJob = null
            runCatching { nativeRuntime.shutdown() }
                .onFailure { Log.w(TAG, "Native shutdown reported a problem", it) }
            // With the native bootstrap released only the frontend executor remains.
            if (_state.value == ShellRuntimeState.NativeBootstrapReady) {
                _state.value = ShellRuntimeState.FrontendOnly
            }
        }
    }

    private companion object {
        const val TAG = "AliasNullRuntimeManager"
    }
}
