package app.aliasnull.shell.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Availability of a [TerminalSessionEngine]: whether the terminal engine contract
 * exists versus whether a real interactive-terminal session backend exists.
 *
 * These two questions are kept separate so that "the session API exists" can never
 * be mistaken for "an interactive terminal can run commands". [contractPresent] is
 * true for any engine whose Kotlin contract is in the codebase; [sessionBackendAvailable]
 * is true only when a real process/PTY backend is bound and could genuinely host a
 * session - which is never the case in this milestone.
 *
 * A dedicated type is used (rather than reusing ExecutionBackendAvailability)
 * because ExecutionBackendStatus answers a different question - which command
 * execution backend is active - and none of its values expresses "the engine
 * contract exists but hosts no terminal backend". The command-execution story stays
 * entirely with ExecutionBackendAvailability; this type does not replace it.
 */
data class TerminalEngineAvailability(
    /** True whenever the terminal session engine API/contract is present. */
    val contractPresent: Boolean,
    /** True only when a real terminal process/PTY backend is bound and ready. */
    val sessionBackendAvailable: Boolean,
    val message: String,
) {
    /** True only when a session could genuinely be hosted and run right now. */
    val canHostTerminalSession: Boolean
        get() = contractPresent && sessionBackendAvailable

    companion object {
        /** Availability of the contract-only foundation: API present, no backend. */
        fun contractOnly(): TerminalEngineAvailability = TerminalEngineAvailability(
            contractPresent = true,
            sessionBackendAvailable = false,
            message = "The terminal session engine contract is present, but no terminal process/PTY backend exists yet. The engine cannot host a session in this milestone.",
        )
    }
}

/**
 * Honest classification of one terminal-session lifecycle operation (open/close),
 * mirroring the pattern of the native session foundation's operation outcomes so a
 * caller can tell exactly why an operation did not proceed.
 */
enum class TerminalSessionOutcome {
    /** [TerminalSessionEngine.openSession] created a session; its id is valid and READY. */
    SESSION_OPENED,

    /** A session was closed, or close was a benign idempotent no-op (never-open/already-closed). */
    SESSION_CLOSED,

    /** An operation referenced a session id that is not a live session. */
    SESSION_UNAVAILABLE,

    /** The engine has no terminal backend, so the operation cannot proceed at all. */
    ENGINE_UNAVAILABLE,

    /** An unexpected exception surfaced outside the expected paths. */
    UNEXPECTED,
}

/**
 * Honest classification of one [TerminalSessionEngine.sendInput] attempt. Kept
 * separate from the lifecycle outcome so a caller can distinguish every reason an
 * input was not delivered: the session is unknown ([SESSION_UNAVAILABLE]), the
 * engine has no backend ([ENGINE_UNAVAILABLE]), the session exists but input is not
 * wired yet ([INPUT_NOT_IMPLEMENTED]), or the session is already closed
 * ([SESSION_CLOSED]). Input is never silently dropped: every rejection returns one
 * of these.
 */
enum class TerminalInputOutcome {
    /** The input was accepted and handed to the session backend. */
    ACCEPTED,

    /** The targeted session id is not a live session. */
    SESSION_UNAVAILABLE,

    /** The engine has no terminal backend, so input cannot be delivered. */
    ENGINE_UNAVAILABLE,

    /** The session exists but delivering input is not implemented yet. */
    INPUT_NOT_IMPLEMENTED,

    /** The targeted session is already closed. */
    SESSION_CLOSED,

    /** An unexpected exception surfaced. */
    UNEXPECTED,
}

/**
 * Structured outcome of one terminal-session lifecycle operation. [sessionId] is
 * [TerminalSessionId.NO_SESSION] unless the operation produced or targeted a real
 * engine session; [state] is populated on successful open (READY) and close
 * (CLOSED).
 */
data class TerminalSessionResult(
    val outcome: TerminalSessionOutcome,
    val sessionId: TerminalSessionId = TerminalSessionId.NO_SESSION,
    val state: TerminalSessionState? = null,
    val message: String = "",
) {
    val success: Boolean
        get() = outcome == TerminalSessionOutcome.SESSION_OPENED ||
            outcome == TerminalSessionOutcome.SESSION_CLOSED

    companion object {
        fun unexpected(error: Throwable) = TerminalSessionResult(
            outcome = TerminalSessionOutcome.UNEXPECTED,
            message = "Terminal session operation error: ${error.message ?: error::class.simpleName ?: "unknown"}",
        )
    }
}

/**
 * Structured outcome of one [TerminalSessionEngine.sendInput] attempt; [accepted]
 * is true only for [TerminalInputOutcome.ACCEPTED].
 */
data class TerminalInputResult(
    val outcome: TerminalInputOutcome,
    val message: String = "",
) {
    val accepted: Boolean
        get() = outcome == TerminalInputOutcome.ACCEPTED

    companion object {
        fun unexpected(error: Throwable) = TerminalInputResult(
            outcome = TerminalInputOutcome.UNEXPECTED,
            message = "Terminal input error: ${error.message ?: error::class.simpleName ?: "unknown"}",
        )
    }
}

/**
 * The terminal-session engine contract: the future owner of live terminal sessions,
 * each with identity, lifecycle, an input boundary, an output event boundary and a
 * deterministic close boundary.
 *
 * This is a CONTRACT foundation only in Part 26-K. It is deliberately NOT a
 * [app.aliasnull.shell.execution.ShellCommandExecutor], is NOT registered with the
 * execution routing layer, and does NOT route, replace or bypass
 * ExecutionRouter. Commands keep flowing to the temporary frontend executor through
 * the existing, unchanged path. Nothing here forks, execs, spawns, creates a PTY or
 * calls JNI, and no live session is ever opened by the foundation of this
 * milestone.
 *
 * Conceptually a future real engine hosts sessions as follows:
 *
 *   TerminalSessionEngine
 *       ├── lifecycle boundary      -> [stateOf] + [TerminalSessionState]
 *       ├── send-input boundary     -> [sendInput]
 *       ├── output event boundary   -> [outputEventsOf] + [TerminalSessionEvent]
 *       ├── state event boundary    -> [stateEventsOf] + [TerminalSessionState]
 *       ├── state observation       -> [stateOf] / [TerminalSessionOutcome]
 *       └── close boundary          -> [closeSession] / [shutdown]
 *
 * The engine may create (reserve) a session with [openSession] and must release
 * every session it created on [shutdown]. Implementations that open sessions own
 * them deterministically: [closeSession] must be idempotent (safe to call
 * repeatedly, safe before any open) and [shutdown] must never leak a
 * contract-level session. The foundation of this milestone opens nothing, so those
 * guarantees hold vacuously.
 */
interface TerminalSessionEngine {

    /** Whether the engine contract exists and whether a real session backend exists. */
    val availability: TerminalEngineAvailability

    /**
     * Reserves (opens) a terminal session. A successful result is
     * [TerminalSessionOutcome.SESSION_OPENED] with a READY session - nothing runs.
     * The contract-only foundation reports [TerminalSessionOutcome.ENGINE_UNAVAILABLE].
     */
    fun openSession(): TerminalSessionResult

    /** Lifecycle state of a live session, or null when the id is not live. */
    fun stateOf(sessionId: TerminalSessionId): TerminalSessionState?

    /** The live session's output/event stream, or null when the id is not live. */
    fun outputEventsOf(sessionId: TerminalSessionId): Flow<TerminalSessionEvent>?

    /**
     * Observes the lifecycle state of the live session [sessionId] over time, or
     * null when the id is not live or the engine hosts no state stream for it.
     *
     * This is the state *observation* boundary (Part 26-Q), distinct from the
     * one-shot [stateOf] query and from [outputEventsOf]'s output/content stream.
     * A future real backend emits [TerminalSessionState] transitions through it so a
     * bound UI session can track lifecycle without polling [stateOf]. It does not
     * execute commands, create or close sessions, own UI state, or imply a process
     * or PTY exists. The contract-only foundation hosts no live session, so it
     * returns null and never emits a fabricated lifecycle state.
     */
    fun stateEventsOf(sessionId: TerminalSessionId): Flow<TerminalSessionState>?

    /**
     * Sends [content] to a live session's input boundary. Never executed in this
     * milestone: the foundation rejects with an explicit [TerminalInputOutcome]
     * rather than dropping input silently. A caller can distinguish every reason.
     */
    fun sendInput(sessionId: TerminalSessionId, content: String): TerminalInputResult

    /** Closes a session deterministically; idempotent for unknown/already-closed ids. */
    fun closeSession(sessionId: TerminalSessionId): TerminalSessionResult

    /** Releases every session the engine owns. Safe to call repeatedly; leaks nothing. */
    fun shutdown()
}
