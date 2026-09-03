package app.aliasnull.ui.screens.shell

import androidx.compose.ui.text.input.TextFieldValue

/**
 * One independent terminal session. Each session owns its own rendered history,
 * current input line, in-memory command history, and command-history browsing
 * position, so switching sessions never leaks state between them.
 *
 * [title] is dynamic session state: it is generated when the session is created
 * and may later be replaced by runtime-derived context once a real backend
 * exists. It is never a hardcoded, UI-level session label.
 */
data class TerminalSession(
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
