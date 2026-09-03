package app.aliasnull.shell.terminal

/**
 * One terminal-session event produced by a live engine session over time.
 *
 * This is the output event boundary of the terminal session engine and is scoped
 * to terminal-session lifecycle/content. It is intentionally NOT a
 * command-execution stream: [app.aliasnull.shell.execution.ShellExecutionEvent]
 * remains the contract for the batch command-executor path, and this model does
 * not replace or overlap it.
 *
 * The model is future-facing: it can represent terminal-oriented events without
 * pretending a terminal exists. The contract-only foundation never produces a live
 * session and therefore never emits any event; [Output] and [Error] are the shapes
 * a future real backend will emit. No command output, shell prompt, process output
 * or ANSI sequence is ever fabricated here.
 */
sealed interface TerminalSessionEvent {

    /**
     * A chunk of terminal output bytes/text for the session. Reserved for a real
     * backend; never emitted by this milestone and never manufactured.
     */
    data class Output(val content: String) : TerminalSessionEvent

    /**
     * An engine/infrastructure error for this session - user-safe text, never a raw
     * stack trace. A real backend may report problems through this; it is not
     * command output and is not a fabricated process message.
     */
    data class Error(val message: String) : TerminalSessionEvent
}
