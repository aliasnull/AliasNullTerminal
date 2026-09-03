package app.aliasnull.shell.execution

/**
 * One unit of terminal execution produced by a [ShellCommandExecutor].
 *
 * A single command submission emits a stream of these events over time so that a
 * long-running command can stream output instead of producing one monolithic
 * result. Normal commands end with [Completed]; an infrastructure-level crash
 * ends with [Failed] and never with [Completed]. A message the command itself
 * produced (e.g. "command not found") is [Error] output - that is a command
 * result, not an infrastructure failure, and the two must stay distinct.
 */
sealed interface ShellExecutionEvent {

    /** Execution of the requested command has begun. Carries no terminal text. */
    data object Started : ShellExecutionEvent

    /** A chunk of normal terminal output to append to the session history. */
    data class Output(val content: String) : ShellExecutionEvent

    /** A chunk of error output the command itself produced (still a command result). */
    data class Error(val content: String) : ShellExecutionEvent

    /** The command finished. [exitCode] 0 means success; carries no terminal text. */
    data class Completed(val exitCode: Int = 0) : ShellExecutionEvent

    /**
     * The execution infrastructure failed before the command completed. The
     * session must be restored to idle, and [message] is user-safe text (never a
     * raw stack trace).
     */
    data class Failed(val message: String) : ShellExecutionEvent

    /**
     * Terminal-level request from a command (currently the `clear` command):
     * wipe the rendered history. Kept separate from ordinary output so the
     * ViewModel can clear session history as a clean state operation instead of
     * manufacturing fake output lines.
     */
    data object ClearScreen : ShellExecutionEvent
}
