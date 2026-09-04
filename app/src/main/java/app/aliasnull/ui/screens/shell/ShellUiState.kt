package app.aliasnull.ui.screens.shell

import androidx.compose.ui.text.input.TextFieldValue
import app.aliasnull.shell.terminal.TerminalSessionId
import app.aliasnull.shell.terminal.TerminalSessionState

/**
 * One independent Shell UI session. Each session owns its own rendered history,
 * current input line, in-memory command history, and command-history browsing
 * position, so switching sessions never leaks state between them.
 *
 * [title] is dynamic session state: it is generated when the session is created
 * and may later be replaced by runtime-derived context once a real backend
 * exists. It is never a hardcoded, UI-level session label.
 *
 * Identity reconciliation (Part 26-M): [id] is UI-local - a per-process counter
 * used to select the active tab and to correlate command submissions. It is NOT
 * an engine identity. [engineSessionId] is the only place an association to a
 * future TerminalSessionEngine session may live, and it stays null until a real
 * backend opens a session: a UI session never fabricates an engine session.
 */
data class TerminalSession(
    /**
     * This UI session's key within the process: selects the active tab/session and
     * is passed as the ShellExecutionRequest session id on the batch command path.
     * Generated per process from a counter, never persisted, and not an engine
     * identity, a native slot id, or a PID. See [engineSessionId].
     */
    val id: Long,
    val title: String,
    val entries: List<TerminalEntry> = emptyList(),
    val input: TextFieldValue = TextFieldValue(""),
    /** Submitted non-empty commands for this session, oldest first. */
    val commandHistory: List<String> = emptyList(),
    /** Index into [commandHistory] while browsing with Up/Down; -1 = not browsing. */
    val historyIndex: Int = -1,
    /** The text being edited before the user started browsing this session's history. */
    val historyDraft: String = "",
    /**
     * True while a command submitted to this session is still executing. Guards
     * against starting a second execution in the same session before the first
     * completes; when a real runtime streams output this flips back to false on
     * completion so the session is ready for the next command.
     */
    val isExecuting: Boolean = false,

    /**
     * Optional association to a future TerminalSessionEngine session. Always null
     * today because the engine is contract-only (no session backend exists): a UI
     * session legitimately exists with no engine session, and absence is the honest
     * default. A real backend would set this exactly once when it opens an engine
     * session for this UI session. It is never fabricated, never equals [id], and
     * is not a process/PTY handle.
     */
    val engineSessionId: TerminalSessionId? = null,

    /**
     * Latest genuine lifecycle state reported by the engine session bound to
     * [engineSessionId], or null when no genuine engine lifecycle state is currently
     * available. Always null today (the engine backend is unavailable, so
     * [engineSessionId] is null). It is never initialized to READY/ACTIVE or any
     * other fabricated value, and it does not imply a process or PTY exists.
     */
    val engineSessionState: TerminalSessionState? = null,
)

/**
 * Immutable snapshot of the Shell screen state, exposed by [ShellViewModel].
 *
 * Holds all open sessions plus the id of the one currently on screen. At least
 * one session is always present; [activeSession] is null only in the transient
 * default state before [ShellViewModel] creates its first session.
 */
data class ShellUiState(
    val sessions: List<TerminalSession> = emptyList(),
    val activeSessionId: Long = NO_SESSION,
) {
    val activeSession: TerminalSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    companion object {
        const val NO_SESSION = -1L
    }
}
