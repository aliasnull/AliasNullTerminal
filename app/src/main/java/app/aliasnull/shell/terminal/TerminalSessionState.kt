package app.aliasnull.shell.terminal

/**
 * Lifecycle state of one terminal-session engine session.
 *
 * This vocabulary describes the lifecycle of a future interactive terminal session
 * contract. It is deliberately a distinct semantic layer from the native slot
 * vocabulary [app.aliasnull.shell.runtime.native.NativeSessionState]: when a real
 * process eventually exists it will live in a native slot whose state may reach
 * RUNNING, while the engine session above it tracks its own lifecycle. No state in
 * this vocabulary implies a process is running.
 *
 * Honest reachability in this milestone:
 *
 *   - [STARTING], [ACTIVE] and [CLOSING] are reserved for the future phase in
 *     which a real process/PTY backend binds a session.
 *   - [ACTIVE] names an active engine session boundary only; it never means "a
 *     shell process exists".
 *
 * The contract-only foundation never opens a session, so no session transitions
 * past [UNINITIALIZED]; it never fabricates a STARTING/ACTIVE/RUNNING-like state.
 */
enum class TerminalSessionState {
    /** Default for a session that has not been opened yet. */
    UNINITIALIZED,

    /** Opened and validated; awaiting a real backend binding. Nothing is running. */
    READY,

    /** Reserved future transition while a real backend starts. Never set by this milestone. */
    STARTING,

    /** An active engine session boundary. Never a running process in this milestone. */
    ACTIVE,

    /** Reserved future transition while a session is being closed. */
    CLOSING,

    /** Terminal state: the session has been closed and retired. */
    CLOSED,

    /** The engine reported an error for this session. */
    ERROR,
}
