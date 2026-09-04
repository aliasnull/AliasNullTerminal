package app.aliasnull.ui.screens.shell

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aliasnull.shell.execution.ShellExecutionEvent
import app.aliasnull.shell.execution.ShellExecutionRequest
import app.aliasnull.shell.runtime.AliasNullRuntimeManager
import app.aliasnull.shell.runtime.NativeProcessExecutionResult
import app.aliasnull.shell.runtime.NativeProcessTestKind
import app.aliasnull.shell.runtime.NativeProcessTestResult
import app.aliasnull.shell.runtime.ShellBackendPhase
import app.aliasnull.shell.runtime.ShellRuntimeManager
import app.aliasnull.shell.terminal.TerminalInputOutcome
import app.aliasnull.shell.terminal.TerminalInputResult
import app.aliasnull.shell.terminal.TerminalSessionEvent
import app.aliasnull.shell.terminal.TerminalSessionId
import app.aliasnull.shell.terminal.TerminalSessionOutcome
import app.aliasnull.shell.terminal.TerminalSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder for the Shell screen.
 *
 * Owns the list of independent terminal sessions and the active session id, and
 * translates commands, key presses and execution events into per-session history
 * entries. Each session keeps its own rendered history, in-memory command
 * history, browsing position, input line and execution state; switching sessions
 * never moves state between them.
 *
 * UI session identity is deliberately UI-local. [TerminalSession.id] is a
 * per-process counter used to select the active tab and to correlate command
 * submissions ([ShellExecutionRequest] session id); it is not a TerminalSessionId
 * and never becomes one. No engine session is opened for a UI session while the
 * terminal engine is contract-only, and closing a UI session that has no engine
 * association needs no engine call. If a future backend ever associates an engine
 * session, this ViewModel is where that optional association would be bridged from
 * the runtime's engine boundary - never fabricated. Runtime shutdown remains the
 * single engine cleanup authority (ShellRuntimeManager.terminalSessionEngine).
 *
 * Part 26-O wires that reconciliation into the UI lifecycle: creating a UI session
 * requests an engine session through [ShellRuntimeManager.terminalSessionOrchestrator]
 * (see [createSession]), and closing a UI session that holds a genuine engine
 * association releases it through the same boundary (see [closeSession]). The
 * request is synchronous and the current engine backend is unavailable, so the
 * honest outcome is ENGINE_UNAVAILABLE and every session keeps engineSessionId ==
 * null; the UI session always exists normally regardless of the engine result.
 *
 * Part 26-P adds the engine-output observation seam and keeps it dormant: a UI
 * session whose engine association is genuine observes that engine session's output
 * events and appends each one to its own history - never to the active tab by
 * default - and closing the UI session cancels the observation. Today no UI session
 * ever holds a genuine association (the engine backend is unavailable), so no
 * observer starts, nothing is collected and no event is fabricated; the seam is
 * only exercised when a real backend exists.
 *
 * Part 26-Q adds the matching lifecycle-state observation seam and keeps it dormant
 * too: a UI session whose engine association is genuine also observes that engine
 * session's [TerminalSessionState] updates and stores the latest one on the session
 * - never routed through the active tab - and closing the UI session cancels that
 * observation. Today no UI session holds a genuine association, so no state observer
 * starts, [TerminalSession.engineSessionState] stays null and no lifecycle state is
 * ever fabricated; the seam is only exercised when a real backend exists.
 *
 * Part 26-R adds the interactive-input seam and keeps it unreachable: a private,
 * synchronous boundary ([sendInputToEngineSession]) through which a future
 * engine-bound UI session forwards input to its genuine engine session via the
 * existing `TerminalSessionEngine.sendInput`. It is invoked only with a genuine
 * stored association - never from [activeSessionId], never synthesized - and returns
 * the engine's own input result unchanged, so no input vocabulary is invented. Today
 * every UI session has engineSessionId == null, so no engine input is ever sent and
 * [submitCommand]'s existing batch path is intentionally byte-for-byte unchanged; a
 * future milestone defines the product semantics that route an engine-bound session
 * through this seam.
 *
 * Part 26-S centralizes the association rule those dormant seams already guard: the
 * single private predicate ([isBoundToEngineSession]) that asks "does this exact UI
 * session still own this exact TerminalSessionId?", now applied consistently by the
 * output-event, lifecycle-state and engine-input boundaries before any engine data is
 * applied or sent. Engine close keeps using only the genuine association read from the
 * closing session. Today every UI session has engineSessionId == null, so no boundary
 * ever validates a real association and no engine interaction occurs.
 *
 * Command execution is delegated to the runtime's [ShellCommandExecutor] (see
 * [ShellRuntimeManager]); this ViewModel never parses or simulates commands
 * itself. A submitted command is appended immediately, then the executor's event
 * stream is collected asynchronously and each event is applied to the session
 * that submitted it - so a command can stream output over time, and output can
 * never land in the wrong session even while another session is on screen.
 * Command history is intentionally in-memory and is lost when the process is
 * recreated.
 *
 * This is an [AndroidViewModel] only so the runtime manager can receive the
 * [Application] context for application-private storage. Creating the ViewModel
 * (the first time the Shell is opened) also kicks off the honest native runtime
 * bootstrap and AN Shell core verification via [ShellRuntimeManager.initialize];
 * the Shell's command gate ([ShellBackendPhase]) is published by the runtime and
 * only ever observed here, never derived or replaced. Commands are submitted only
 * while that gate is READY (see [submitCommand]).
 */
class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime: ShellRuntimeManager by lazy {
        AliasNullRuntimeManager(getApplication())
    }

    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    /** In-flight executions keyed by session id; at most one per session. */
    private val executionJobs = mutableMapOf<Long, Job>()

    /**
     * In-flight terminal-session event observations keyed by UI session id; at most
     * one per session, and only ever populated when the session holds a genuine
     * engine association. Deliberately separate from [executionJobs]: command
     * execution and engine observation have independent lifecycles, so submitting a
     * command or completing an execution must not cancel an active observation and
     * vice versa.
     */
    private val terminalEventJobs = mutableMapOf<Long, Job>()

    /**
     * In-flight terminal-session lifecycle-state observations keyed by UI session id;
     * at most one per session, and only ever populated when the session holds a
     * genuine engine association. Deliberately separate from [executionJobs] and
     * [terminalEventJobs]: lifecycle-state observation, output-event observation and
     * command execution are independent streams with independent lifecycles, so an
     * operation on one never cancels another.
     */
    private val terminalStateJobs = mutableMapOf<Long, Job>()

    private var nextSessionId = 0L
    private var nextEntryId = 0L
    private var sessionOrdinal = 0

    init {
        // Always start with one active session.
        createSession()
        // Start the runtime initialization when the Shell is first needed; it is
        // asynchronous, idempotent and never blocks the UI thread.
        runtime.initialize()
        // Keep the Shell's command gate in step with the runtime: the gate value is
        // published by the runtime at real verification points and is only observed
        // here - never guessed, never re-derived from another state machine.
        observeRuntimeStatus()
    }

    // ---- Session management ----

    /**
     * Creates a new session, assigns it a dynamic title, and activates it.
     *
     * The normal UI session is created first, then it requests an engine session
     * through the runtime orchestration boundary ([ShellRuntimeManager.terminalSessionOrchestrator])
     * using this session's raw [Long] id. Only a genuine SESSION_OPENED result
     * attaches a real [TerminalSessionId] to the session; any other outcome keeps
     * [TerminalSession.engineSessionId] null and never fabricates an id. The
     * current engine backend is unavailable, so every new session stays unattached
     * and still exists normally. When an association is genuine, the session also
     * starts observing that engine session's output and lifecycle state (see
     * [startObservingEngineSession], [startObservingEngineState]).
     */
    fun createSession() {
        val id = nextSessionId++
        val title = nextSessionTitle()
        val base = TerminalSession(id = id, title = title, entries = banner())
        val engineId = requestEngineSession(id)
        val session = if (engineId == null) base else base.copy(engineSessionId = engineId)
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions + session,
                activeSessionId = id,
            )
        }
        // Only a genuine engine association is ever observed. Today the engine
        // backend is unavailable, so engineId is always null and no observer starts.
        if (engineId != null) {
            startObservingEngineSession(id, engineId)
            startObservingEngineState(id, engineId)
        }
    }

    /**
     * Requests an engine session for the UI session [uiSessionId] through the
     * runtime orchestration boundary and returns a genuine engine session id only
     * when the engine reports SESSION_OPENED with a real (non-NO_SESSION) id.
     * Any other outcome - today ENGINE_UNAVAILABLE - returns null so the UI
     * session stays unattached. This ViewModel never converts [uiSessionId] into a
     * [TerminalSessionId] and never fabricates one.
     */
    private fun requestEngineSession(uiSessionId: Long): TerminalSessionId? {
        val result = runtime.terminalSessionOrchestrator.requestSessionForUiSession(uiSessionId)
        return if (
            result.outcome == TerminalSessionOutcome.SESSION_OPENED &&
            result.sessionId != TerminalSessionId.NO_SESSION
        ) {
            result.sessionId
        } else {
            null
        }
    }

    /** Makes the session with [id] the active one, restoring its terminal. */
    fun switchSession(id: Long) {
        _uiState.update { state ->
            if (id == state.activeSessionId || state.sessions.none { it.id == id }) {
                state
            } else {
                state.copy(activeSessionId = id)
            }
        }
    }

    /**
     * Closes the session with [id]; refuses to remove the last remaining session.
     *
     * Any in-flight UI execution, engine-output observation and engine-state
     * observation are cancelled first. A closing session releases an
     * engine association only when it holds a genuine one: a non-null
     * [TerminalSession.engineSessionId] is delegated to the runtime orchestration
     * boundary ([ShellRuntimeManager.terminalSessionOrchestrator]), whose engine
     * close is idempotent for unknown/already-closed ids. Today every UI session
     * has a null association, so no engine close is requested here and no fake id
     * is ever fabricated - a UI session with no engine session closes without an
     * engine call.
     */
    fun closeSession(id: Long) {
        val snapshot = _uiState.value
        val willRemove = snapshot.sessions.size > 1 && snapshot.sessions.any { it.id == id }
        if (willRemove) {
            // Cancel any in-flight execution before removing the session so its event
            // stream cannot keep running after the session disappears.
            executionJobs.remove(id)?.cancel()
            // Cancel any in-flight engine-output observation before removing the
            // session and before releasing the engine association it was attached to.
            terminalEventJobs.remove(id)?.cancel()
            // Cancel any in-flight engine-state observation before removing the
            // session and before releasing the engine association it was attached to.
            terminalStateJobs.remove(id)?.cancel()
            // Release a genuine engine association through the orchestration
            // boundary. engineSessionId is null today, so this never fires.
            val engineId = snapshot.sessions.firstOrNull { it.id == id }?.engineSessionId
            if (engineId != null) {
                runtime.terminalSessionOrchestrator.closeSessionForUiSession(id, engineId)
            }
        }
        _uiState.update { state ->
            if (state.sessions.size <= 1) return@update state
            val index = state.sessions.indexOfFirst { it.id == id }
            if (index < 0) return@update state
            val remaining = state.sessions.filterNot { it.id == id }
            val nextActive = if (state.activeSessionId == id) {
                // Fall back to the session now occupying the closed slot (or the last one).
                remaining[minOf(index, remaining.lastIndex)].id
            } else {
                state.activeSessionId
            }
            state.copy(sessions = remaining, activeSessionId = nextActive)
        }
    }

    // ---- Active-session editing ----

    fun onInputChanged(value: TextFieldValue) {
        updateActiveSession { session ->
            if (session.historyIndex == -1) {
                session.copy(input = value)
            } else {
                // The user edited the text, so leave history-browsing mode.
                session.copy(input = value, historyIndex = -1, historyDraft = "")
            }
        }
    }

    /**
     * Submits the active session's command line and starts execution.
     *
     * A command is executed only while the Shell's command gate is
     * [ShellBackendPhase.READY] (the AN Shell core bridge verified READY). While
     * the gate is INITIALIZING or FAILED nothing is executed and no command output
     * is fabricated; the defensive check below guards every submit/IME path, and
     * the UI additionally hides the terminal while the gate is not READY.
     *
     * The command entry is appended and the session is marked executing
     * synchronously; the runtime executor is then collected asynchronously and
     * each [ShellExecutionEvent] is applied to the submitting session as it
     * arrives. While a session is executing, further submits to that session are
     * ignored so a second execution cannot start before the first completes.
     */
    fun submitCommand() {
        val snapshot = _uiState.value
        val session = snapshot.activeSession ?: return
        if (snapshot.runtimeStatus.phase != ShellBackendPhase.READY) return
        val command = session.input.text.trim()
        if (command.isEmpty() || session.isExecuting) return

        val enqueued = session.copy(
            commandHistory = session.commandHistory + command,
            historyIndex = -1,
            historyDraft = "",
            input = TextFieldValue(""),
            isExecuting = true,
        ).withEntry(entry(TerminalEntryType.COMMAND, command))
        updateSession(session.id) { enqueued }

        executionJobs.remove(session.id)?.cancel()
        val job = viewModelScope.launch {
            try {
                runtime.executor.execute(
                    ShellExecutionRequest(sessionId = session.id, command = command),
                ).collect { event ->
                    applyExecutionEvent(event, session.id)
                }
            } catch (cancelled: CancellationException) {
                // Session torn down or execution cancelled; not a command failure.
                throw cancelled
            } catch (t: Throwable) {
                // The execution layer itself failed: never crash the screen or lose
                // the command silently. Report a user-safe error and restore idle.
                appendToSession(session.id, TerminalEntryType.ERROR, "Shell execution error: ${t.message ?: "unexpected failure"}")
                completeExecution(session.id)
            }
        }
        // An execution that finished before the job was stored (an already-completed
        // event burst) must not linger in the live-executions map.
        if (job.isCompleted) executionJobs.remove(session.id) else executionJobs[session.id] = job
    }

    /** Recall the previous command of the active session (Up). */
    fun previousCommand() = browseHistory(-1)

    /** Recall the next command, or the in-progress draft, of the active session (Down). */
    fun nextCommand() = browseHistory(1)

    /** Routes a press from the on-screen shortcut bar to the active session. */
    fun onExtraKey(key: TerminalKey) {
        when (key) {
            TerminalKey.UP -> previousCommand()
            TerminalKey.DOWN -> nextCommand()
            TerminalKey.HOME -> moveCaretToStart()
            TerminalKey.END -> moveCaretToEnd()
            TerminalKey.LEFT -> moveCursor(-1)
            TerminalKey.RIGHT -> moveCursor(1)
            TerminalKey.TAB -> insertText("\t")
            TerminalKey.SLASH -> insertText("/")
            TerminalKey.BRACES -> insertPair("{", "}")
            TerminalKey.PARENS -> insertPair("(", ")")
            TerminalKey.BRACKETS -> insertPair("[", "]")
            // ESC cancels an in-progress command-history recall. CTRL/ALT have no
            // meaning yet: no AN runtime is connected, so they stay as the
            // modifier-key foundation a future shell backend can build on.
            TerminalKey.ESC -> cancelHistoryBrowse()
            TerminalKey.CTRL, TerminalKey.ALT -> Unit
        }
    }

    // ---- Execution event handling ----

    /** Translates one executor event into a change of the [sessionId] session's state. */
    private fun applyExecutionEvent(event: ShellExecutionEvent, sessionId: Long) {
        when (event) {
            ShellExecutionEvent.Started -> setSessionExecuting(sessionId, true)
            is ShellExecutionEvent.Output -> appendToSession(sessionId, TerminalEntryType.OUTPUT, event.content)
            is ShellExecutionEvent.Error -> appendToSession(sessionId, TerminalEntryType.ERROR, event.content)
            ShellExecutionEvent.ClearScreen -> clearSessionHistory(sessionId)
            is ShellExecutionEvent.Completed -> completeExecution(sessionId)
            is ShellExecutionEvent.Failed -> {
                appendToSession(sessionId, TerminalEntryType.ERROR, "Shell execution failed: ${event.message}")
                completeExecution(sessionId)
            }
        }
    }

    private fun appendToSession(sessionId: Long, type: TerminalEntryType, content: String) {
        if (content.isEmpty()) return
        updateSession(sessionId) { it.withEntry(entry(type, content)) }
    }

    private fun clearSessionHistory(sessionId: Long) {
        updateSession(sessionId) { it.copy(entries = emptyList()) }
    }

    private fun setSessionExecuting(sessionId: Long, executing: Boolean) {
        updateSession(sessionId) {
            if (it.isExecuting == executing) it else it.copy(isExecuting = executing)
        }
    }

    private fun completeExecution(sessionId: Long) {
        executionJobs.remove(sessionId)
        setSessionExecuting(sessionId, false)
    }

    // ---- Engine event observation ----

    /**
     * Begins observing the engine session [engineSessionId]'s output events and
     * routes each one to the UI session [uiSessionId].
     *
     * An observer is only ever started when a UI session exists and holds a genuine
     * engine association, so this is called with a real engine id only when
     * [requestEngineSession] produced one - which is never while the engine backend
     * is unavailable. The engine itself still has the final say: if `outputEventsOf`
     * returns null (the contract-only foundation does), no observer is started. A
     * previously started observation for this UI session is replaced so at most one
     * collector runs per session.
     */
    private fun startObservingEngineSession(uiSessionId: Long, engineSessionId: TerminalSessionId) {
        val events = runtime.terminalSessionEngine.outputEventsOf(engineSessionId) ?: return
        terminalEventJobs.remove(uiSessionId)?.cancel()
        val job = viewModelScope.launch {
            try {
                events.collect { event -> applyEngineEvent(event, uiSessionId, engineSessionId) }
            } catch (cancelled: CancellationException) {
                // Session closed or observation replaced; not an engine failure.
                throw cancelled
            } catch (t: Throwable) {
                // The observation stream failed; never crash the screen or fabricate
                // an engine event. The session simply stops receiving events.
                terminalEventJobs.remove(uiSessionId)
            }
        }
        if (job.isCompleted) terminalEventJobs.remove(uiSessionId) else terminalEventJobs[uiSessionId] = job
    }

    /**
     * Translates one engine output event into an entry on the [uiSessionId] session's
     * history, but only while that session is still bound to the exact engine session
     * [engineSessionId] that produced it (association safety). A stale event for a
     * re-associated or already-removed session is ignored - it never recreates the
     * session, never routes to the active tab, and never fabricates output. The engine
     * event vocabulary is kept intact and is never merged with [ShellExecutionEvent].
     */
    private fun applyEngineEvent(
        event: TerminalSessionEvent,
        uiSessionId: Long,
        engineSessionId: TerminalSessionId,
    ) {
        if (!isBoundToEngineSession(uiSessionId, engineSessionId)) return
        when (event) {
            is TerminalSessionEvent.Output -> appendToSession(uiSessionId, TerminalEntryType.OUTPUT, event.content)
            is TerminalSessionEvent.Error -> appendToSession(uiSessionId, TerminalEntryType.ERROR, event.message)
        }
    }

    // ---- Engine state observation ----

    /**
     * Begins observing the lifecycle state of the engine session [engineSessionId]
     * and stores each genuine state on the UI session [uiSessionId].
     *
     * Mirrors [startObservingEngineSession]: an observer is only ever started when a
     * UI session exists and holds a genuine engine association, and the engine still
     * has the final say - if `stateEventsOf` returns null (the contract-only
     * foundation does), no observer is started. A previously started state
     * observation for this UI session is replaced so at most one collector runs per
     * session.
     */
    private fun startObservingEngineState(uiSessionId: Long, engineSessionId: TerminalSessionId) {
        val states = runtime.terminalSessionEngine.stateEventsOf(engineSessionId) ?: return
        terminalStateJobs.remove(uiSessionId)?.cancel()
        val job = viewModelScope.launch {
            try {
                states.collect { state -> applyEngineSessionState(uiSessionId, engineSessionId, state) }
            } catch (cancelled: CancellationException) {
                // Session closed or observation replaced; not an engine failure.
                throw cancelled
            } catch (t: Throwable) {
                // The state stream failed; never crash the screen or fabricate a
                // lifecycle state. The session simply keeps its last honest state.
                terminalStateJobs.remove(uiSessionId)
            }
        }
        if (job.isCompleted) terminalStateJobs.remove(uiSessionId) else terminalStateJobs[uiSessionId] = job
    }

    /**
     * Stores one genuine engine lifecycle [state] on the UI session [uiSessionId],
     * but only while that session is still bound to the exact engine session
     * [engineSessionId] that produced it (association safety). A stale update for a
     * re-associated or already-removed session is ignored, so a closed session is
     * never resurrected or reassigned by a late state event.
     */
    private fun applyEngineSessionState(
        uiSessionId: Long,
        engineSessionId: TerminalSessionId,
        state: TerminalSessionState,
    ) {
        if (!isBoundToEngineSession(uiSessionId, engineSessionId)) return
        updateSession(uiSessionId) { it.copy(engineSessionState = state) }
    }

    // ---- Engine input seam ----

    /**
     * Forwards [content] as interactive input for the engine session [engineSessionId]
     * genuinely owned by the UI session [uiSessionId].
     *
     * This is the Part 26-R input boundary: the single private place a UI session's
     * input is handed to the existing `TerminalSessionEngine.sendInput`. It is
     * synchronous (no Job, no coroutine, no cancellation system) and returns the
     * engine's own [TerminalInputResult] unchanged, so the engine result is the
     * authority and no input outcome vocabulary is invented here.
     *
     * It must only be called with an engineSessionId read from that UI session's
     * stored association at submit time. The originating [uiSessionId] is re-verified
     * first so a stale id can never send input after the session was closed or
     * re-associated, and input is never targeted by [activeSessionId].
     *
     * Today every UI session has engineSessionId == null, so no caller reaches this
     * seam during normal usage and `TerminalSessionEngine.sendInput` is never
     * invoked. [submitCommand] keeps routing through the batch executor unchanged; a
     * future milestone defines the product semantics that send an engine-bound
     * session's input through here.
     */
    private fun sendInputToEngineSession(
        uiSessionId: Long,
        engineSessionId: TerminalSessionId,
        content: String,
    ): TerminalInputResult {
        // Association safety: the UI session must still be bound to this exact engine
        // session. When it is gone or re-associated, nothing is sent to the engine.
        if (!isBoundToEngineSession(uiSessionId, engineSessionId)) {
            return TerminalInputResult(
                outcome = TerminalInputOutcome.SESSION_UNAVAILABLE,
                message = "No engine input was sent: the UI session is no longer bound to the supplied engine session.",
            )
        }
        return runtime.terminalSessionEngine.sendInput(engineSessionId, content)
    }

    // ---- Association validation ----

    /**
     * True only while the UI session [uiSessionId] currently owns exactly
     * [engineSessionId].
     *
     * This is the single association rule every engine-facing path shares: a UI
     * session may receive engine output/state or forward input only while it is still
     * bound to that exact engine session. [uiSessionId] is used only to locate the UI
     * session - it is never converted into a TerminalSessionId, and activeSessionId is
     * never used as a substitute.
     */
    private fun isBoundToEngineSession(uiSessionId: Long, engineSessionId: TerminalSessionId): Boolean =
        _uiState.value.sessions.any { it.id == uiSessionId && it.engineSessionId == engineSessionId }

    // ---- Shell command gate (observed, not owned) ----

    /**
     * Observes the runtime's Shell gate and mirrors it into
     * [ShellUiState.runtimeStatus]. The gate is published by the runtime at real
     * verification points ([ShellRuntimeManager.shellBackendState]); the ViewModel
     * only reads and exposes it, never owning bridge verification, JNI or any
     * second readiness state machine. The UI branches on its phase.
     */
    private fun observeRuntimeStatus() {
        viewModelScope.launch {
            runtime.shellBackendState.collect { gate ->
                _uiState.update { state ->
                    if (state.runtimeStatus == gate) state else state.copy(runtimeStatus = gate)
                }
            }
        }
    }

    /**
     * Asks the runtime to re-run its real initialization/verification lifecycle
     * after a FAILED gate. The runtime re-verifies the AN Shell core bridge; READY
     * can only be re-established through that genuine verification, never by a
     * manufactured value. No-op when the gate is not FAILED or an attempt runs.
     */
    fun retryInitialize() = runtime.retryInitialize()

    // ---- Part 27-Q native-process self-check (developer diagnostic) ----

    /**
     * Runs exactly one authorized native-process self-check [kind] through the
     * runtime's controlled seam and publishes the genuine structured result on
     * [ShellUiState.nativeProcessTest].
     *
     * This is a labeled developer diagnostic, not a Shell command: [kind] is the
     * only choice a caller makes (the UI never builds a request, argv, cwd,
     * environment or stdin), and the outcome is shown truthfully - NotReady when
     * the native runtime is not loaded/bootstrapped, otherwise the seam's real
     * category and whether the case's stated expectation was met. At most one
     * case runs at a time: a call while one is already in flight is ignored, and
     * the running case is cleared when the run completes. The native run happens
     * off the main thread inside the runtime; a genuinely unexpected failure is
     * surfaced as an [NativeProcessExecutionResult.InternalFailure] outcome,
     * never thrown into the UI or silently dropped.
     */
    fun runNativeProcessTest(kind: NativeProcessTestKind) {
        // One-at-a-time: ignore a request while a self-check is already in flight.
        if (_uiState.value.nativeProcessTest.runningCase != null) return
        _uiState.update { state ->
            state.copy(
                nativeProcessTest = state.nativeProcessTest.copy(runningCase = kind, result = null),
            )
        }
        viewModelScope.launch {
            val result = try {
                runtime.runNativeProcessTest(kind)
            } catch (cancelled: CancellationException) {
                // ViewModel scope cleared or the run cancelled; not a test failure.
                throw cancelled
            } catch (t: Throwable) {
                // The controlled seam itself failed outside its result model: never
                // crash the screen, never leave the panel stuck at Running, and never
                // fabricate a native outcome. Surface it as an internal failure.
                NativeProcessTestResult.Outcome(
                    kind = kind,
                    execution = NativeProcessExecutionResult.InternalFailure(
                        t.message ?: "unexpected failure while running the native process test",
                    ),
                    expectedMet = false,
                )
            }
            _uiState.update { state ->
                val test = state.nativeProcessTest
                // The one-at-a-time guard means this run is still the one in flight
                // unless the state was already replaced, so publish only then.
                if (test.runningCase != kind) {
                    state
                } else {
                    state.copy(
                        nativeProcessTest = NativeProcessTestUiState(runningCase = null, result = result),
                    )
                }
            }
        }
    }

    // ---- Internals ----

    private fun nextSessionTitle(): String {
        sessionOrdinal++
        val base = "Shell"
        return if (sessionOrdinal == 1) base else "$base $sessionOrdinal"
    }

    private fun banner(): List<TerminalEntry> = buildList {
        add(entry(TerminalEntryType.SYSTEM, "AliasNull Shell"))
        add(entry(TerminalEntryType.SYSTEM, "Type 'help' to list the available commands."))
    }

    private fun entry(type: TerminalEntryType, content: String) =
        TerminalEntry(id = nextEntryId++, type = type, content = content)

    private fun browseHistory(direction: Int) {
        updateActiveSession { session ->
            val history = session.commandHistory
            if (history.isEmpty()) return@updateActiveSession session
            val last = history.lastIndex

            if (direction < 0) {
                val index = when {
                    session.historyIndex == -1 -> last
                    session.historyIndex > 0 -> session.historyIndex - 1
                    else -> session.historyIndex
                }
                val draft = if (session.historyIndex == -1) session.input.text else session.historyDraft
                session.copy(
                    input = textValue(history[index]),
                    historyIndex = index,
                    historyDraft = draft,
                )
            } else {
                if (session.historyIndex == -1) return@updateActiveSession session
                if (session.historyIndex < last) {
                    val index = session.historyIndex + 1
                    session.copy(
                        input = textValue(history[index]),
                        historyIndex = index,
                        historyDraft = session.historyDraft,
                    )
                } else {
                    session.copy(
                        input = textValue(session.historyDraft),
                        historyIndex = -1,
                        historyDraft = "",
                    )
                }
            }
        }
    }

    private fun cancelHistoryBrowse() {
        updateActiveSession { session ->
            if (session.historyIndex == -1) {
                session
            } else {
                session.copy(
                    input = textValue(session.historyDraft),
                    historyIndex = -1,
                    historyDraft = "",
                )
            }
        }
    }

    private fun moveCursor(delta: Int) {
        updateActiveSession { session ->
            val value = session.input
            if (value.text.isEmpty()) return@updateActiveSession session
            val target = (value.selection.min + delta).coerceIn(0, value.text.length)
            leaveHistoryBrowse(session).copy(input = value.copy(selection = TextRange(target)))
        }
    }

    private fun moveCaretToStart() {
        updateActiveSession { session ->
            val value = session.input
            if (value.text.isEmpty()) return@updateActiveSession session
            leaveHistoryBrowse(session).copy(input = value.copy(selection = TextRange(0)))
        }
    }

    private fun moveCaretToEnd() {
        updateActiveSession { session ->
            val value = session.input
            if (value.text.isEmpty()) return@updateActiveSession session
            leaveHistoryBrowse(session).copy(
                input = value.copy(selection = TextRange(value.text.length)),
            )
        }
    }

    /** Inserts an open/close pair and leaves the caret between the two halves. */
    private fun insertPair(open: String, close: String) =
        insertText(open + close, caretAfter = open.length)

    private fun insertText(text: String, caretAfter: Int = text.length) {
        updateActiveSession { session ->
            val value = session.input
            val start = value.selection.min.coerceIn(0, value.text.length)
            val end = value.selection.max.coerceIn(start, value.text.length)
            val newText = value.text.substring(0, start) + text + value.text.substring(end)
            leaveHistoryBrowse(session).copy(
                input = TextFieldValue(text = newText, selection = TextRange(start + caretAfter)),
            )
        }
    }

    /** Clears the browsing position while keeping the current input text. */
    private fun leaveHistoryBrowse(session: TerminalSession): TerminalSession =
        if (session.historyIndex == -1) session else session.copy(historyIndex = -1, historyDraft = "")

    private fun textValue(text: String) = TextFieldValue(text = text, selection = TextRange(text.length))

    private fun TerminalSession.withEntry(e: TerminalEntry) = copy(entries = entries + e)

    /** Applies [transform] to the session with [sessionId], leaving every other session untouched. */
    private inline fun updateSession(sessionId: Long, transform: (TerminalSession) -> TerminalSession) {
        _uiState.update { state ->
            state.copy(sessions = state.sessions.map { if (it.id == sessionId) transform(it) else it })
        }
    }

    private inline fun updateActiveSession(transform: (TerminalSession) -> TerminalSession) {
        _uiState.update { state ->
            val session = state.activeSession ?: return@update state
            state.copy(sessions = state.sessions.map { if (it.id == session.id) transform(it) else it })
        }
    }
}
