package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.terminal.TerminalSessionEngine
import app.aliasnull.shell.terminal.TerminalSessionEngineFoundation
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the runtime lifecycle and the [ShellCommandExecutor] the Shell should use.
 *
 * The manager is the Shell-facing abstraction over everything below it:
 *
 *   ShellRuntimeManager
 *       ├── shellBackendState           (the Shell gate: INITIALIZING / READY /
 *       │                                FAILED; READY requires both the AN Shell
 *       │                                core and a verified base userspace)
 *       ├── executor                    (resolved through the execution routing layer;
 *       │                                the AN Shell core backend, which is the only
 *       │                                shell command backend and is executable only
 *       │                                once its bridge is READY)
 *       ├── terminalSessionEngine       (the read-only terminal session engine boundary;
 *       │                                hosted by the contract-only foundation)
 *       ├── terminalSessionOrchestrator (the single UI-session <-> engine-session
 *       │                                coordination boundary, hosted by the
 *       │                                contract-only foundation)
 *       └── state                       (native-foundation lifecycle: the C++ bootstrap
 *                                        and foundation-session axis, distinct from the
 *                                        AN Shell command backend above)
 *
 * Command execution ([executor]) and the terminal session engine
 * ([terminalSessionEngine]) are separate, never merged concerns: the engine is
 * not a [ShellCommandExecutor] and never receives a command from the routing
 * layer.
 *
 * Shell command-backend readiness is [shellBackendState], which reports READY
 * only when BOTH the AN Shell core bridge verifies READY (the authoritative
 * bridge fact, AnShellCoreBridge.currentStatus, surfaced through
 * [backendAvailability]) AND the AliasNull base userspace is installed and
 * verified (Part 27-R). [shellBackendState] therefore never guesses when the
 * Shell may run commands and never falls back to a fake backend. [state] is a
 * separate axis: it tracks the C++ native foundation bootstrap, which never
 * executes commands.
 */
interface ShellRuntimeManager {

    /**
     * Native-foundation lifecycle of this manager: the C++ bootstrap and its
     * foundation-session work ([ShellRuntimeState]). This is NOT the shell
     * command-backend gate; the AN Shell core backend is independent of the C++
     * foundation, and its readiness is [shellBackendState].
     */
    val state: StateFlow<ShellRuntimeState>

    /**
     * The Shell gate: one unambiguous value describing whether the interactive
     * Shell may accept commands. READY is reported only once the AN Shell core
     * bridge genuinely verifies READY AND the AliasNull base userspace is
     * installed and verified; FAILED only after a real attempt completes without
     * a READY core or without a verified base userspace; INITIALIZING while an
     * attempt is running or none has finished. Derived by this runtime from the
     * single authoritative readiness path, never fabricated by a timer or the
     * UI.
     */
    val shellBackendState: StateFlow<ShellBackendState>

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
     * Begins asynchronous initialization: the native-foundation bootstrap, the
     * base-userspace bootstrap (Part 27-R), and then the AN Shell core bridge
     * verification that, together with the verified base userspace, decides
     * [shellBackendState]. Safe to call repeatedly; a new attempt is started
     * only when none is running and the backend is not already READY.
     */
    fun initialize()

    /**
     * Re-runs the real initialization/verification lifecycle after a FAILED gate,
     * so READY can only ever be re-established through genuine bridge verification.
     * No-op when an attempt is already running or the backend is already READY.
     */
    fun retryInitialize()

    /** Releases the runtime foundation. No-op for a manager with nothing bootstrapped. */
    fun shutdown()

    /**
     * Describes whether a specific [ExecutionBackend] can execute commands right
     * now, in honest `ExecutionBackendStatus` terms. The AN Shell core backend
     * reports ACTIVE only while its bridge is READY and otherwise exactly why it is
     * not ready; the native backend reports why it cannot run (library unavailable /
     * not bootstrapped / not implemented). This is the runtime boundary's own
     * answer - no JNI leaks to callers.
     */
    fun backendAvailability(backend: ExecutionBackend): ExecutionBackendAvailability

    /**
     * Runs exactly one authorized native-process self-check case (Part 27-Q) and
     * returns the genuine structured outcome.
     *
     * This is the app-facing controlled boundary over the native process runner.
     * [case] is the only thing a caller chooses - never an executable, argv,
     * cwd, environment or stdin. The underlying request is the single allowlisted
     * invocation [NativeExecutionPolicy] authorizes for that case, so the policy
     * gate always stays in front of the real runner and no UI code ever builds a
     * raw request. This is NOT a Shell command surface: nothing routes an AN
     * Shell command here and the runner remains disconnected from command
     * routing.
     *
     * When the native runtime is not loaded and bootstrapped, or the AliasNull
     * base userspace is not installed and verified (Part 27-R), the case is not
     * attempted and [NativeProcessTestResult.NotReady] is returned - execution
     * is never forced on an unready runtime. The blocking native run happens off
     * the caller's thread on the runtime's background dispatcher, so this
     * suspend function is safe to call from the main thread.
     */
    suspend fun runNativeProcessTest(case: NativeProcessTestKind): NativeProcessTestResult
}
