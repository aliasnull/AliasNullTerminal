package app.aliasnull.ui.screens.shell

import android.app.Application
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.aliasnull.shell.execution.ShellExecutionEvent
import app.aliasnull.shell.execution.ShellExecutionRequest
import app.aliasnull.shell.runtime.AliasNullRuntimeManager
import app.aliasnull.shell.runtime.ShellRuntimeManager
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
 * bootstrap via [ShellRuntimeManager.initialize]; native bootstrap is a separate
 * concern from command execution and never replaces the temporary executor.
 */
class ShellViewModel(application: Application) : AndroidViewModel(application) {

    private val runtime: ShellRuntimeManager by lazy {
        AliasNullRuntimeManager(getApplication())
    }

    private val _uiState = MutableStateFlow(ShellUiState())
    val uiState: StateFlow<ShellUiState> = _uiState.asStateFlow()

    /** In-flight executions keyed by session id; at most one per session. */
    private val executionJobs = mutableMapOf<Long, Job>()

    private var nextSessionId = 0L
    private var nextEntryId = 0L
    private var sessionOrdinal = 0

    init {
        // Always start with one active session.
        createSession()
        // Start the native runtime bootstrap when the Shell is first needed; it is
        // asynchronous, idempotent and never blocks the UI thread.
        runtime.initialize()
    }

    // ---- Session management ----

    /** Creates a new session, assigns it a dynamic title, and activates it. */
    fun createSession() {
        val id = nextSessionId++
        val title = nextSessionTitle()
        _uiState.update { state ->
            state.copy(
                sessions = state.sessions + TerminalSession(id = id, title = title, entries = banner()),
                activeSessionId = id,
            )
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
     * UI sessions today carry no engine association (see [TerminalSession.engineSessionId]),
     * so nothing engine-side is closed here. Should a future backend associate an
     * engine session, releasing it is the engine owner's responsibility at the
     * runtime boundary, not this ViewModel's, and never a fabricated engine close.
     */
    fun closeSession(id: Long) {
        // Cancel any in-flight execution before removing the session so its event
        // stream cannot keep running after the session disappears.
        val snapshot = _uiState.value
        if (snapshot.sessions.size > 1 && snapshot.sessions.any { it.id == id }) {
            executionJobs.remove(id)?.cancel()
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
     * The command entry is appended and the session is marked executing
     * synchronously; the runtime executor is then collected asynchronously and
     * each [ShellExecutionEvent] is applied to the submitting session as it
     * arrives. While a session is executing, further submits to that session are
     * ignored so a second execution cannot start before the first completes.
     */
    fun submitCommand() {
        val snapshot = _uiState.value
        val session = snapshot.activeSession ?: return
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
        // Temporary commands complete synchronously on Main.immediate, before the
        // job is stored; do not keep the finished job in the live-executions map.
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

    // ---- Internals ----

    private fun nextSessionTitle(): String {
        sessionOrdinal++
        val base = "Frontend Shell"
        return if (sessionOrdinal == 1) base else "$base $sessionOrdinal"
    }

    private fun banner(): List<TerminalEntry> = buildList {
        add(entry(TerminalEntryType.SYSTEM, "AliasNull Shell"))
        add(entry(TerminalEntryType.SYSTEM, "Runtime backend not connected - temporary frontend commands only."))
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
