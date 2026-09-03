package app.aliasnull.shell.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Contract-only foundation implementation of [TerminalSessionEngine].
 *
 * This is the honest runtime answer for Part 26-K: the terminal session engine
 * contract exists, but no terminal process/PTY backend exists, so the engine can
 * never host a session. Every operation is therefore rejected or is a benign no-op:
 *
 *   - [openSession]  -> [TerminalSessionOutcome.ENGINE_UNAVAILABLE] (nothing reserved),
 *   - [stateOf]      -> null (no live session can exist),
 *   - [outputEventsOf] -> null (nothing emits),
 *   - [sendInput]    -> [TerminalInputOutcome.ENGINE_UNAVAILABLE] (input is never
 *                       silently dropped - it is always explicitly rejected),
 *   - [closeSession] -> [TerminalSessionOutcome.SESSION_CLOSED] benign no-op
 *                       (no session is or was open), safe to call repeatedly,
 *   - [shutdown]     -> deterministic no-op (there is no registry to clear).
 *
 * The foundation is pure Kotlin. It never touches libaliasnull_runtime.so, never
 * creates, inspects or closes a native session slot, and never alters the single
 * Part 26-H foundation session owned by the runtime manager. It creates no session
 * per command, per UI refresh or per recomposition - it creates no session at all.
 */
object TerminalSessionEngineFoundation : TerminalSessionEngine {

    override val availability: TerminalEngineAvailability = TerminalEngineAvailability.contractOnly()

    override fun openSession(): TerminalSessionResult = TerminalSessionResult(
        outcome = TerminalSessionOutcome.ENGINE_UNAVAILABLE,
        message = "No terminal process/PTY backend exists; a terminal session cannot be opened. Nothing was started or reserved.",
    )

    override fun stateOf(sessionId: TerminalSessionId): TerminalSessionState? = null

    override fun outputEventsOf(sessionId: TerminalSessionId): Flow<TerminalSessionEvent>? = null

    override fun sendInput(sessionId: TerminalSessionId, content: String): TerminalInputResult =
        TerminalInputResult(
            outcome = TerminalInputOutcome.ENGINE_UNAVAILABLE,
            message = "The terminal engine is contract-only (no process backend). Input was not executed and was not silently dropped.",
        )

    override fun closeSession(sessionId: TerminalSessionId): TerminalSessionResult =
        TerminalSessionResult(
            outcome = TerminalSessionOutcome.SESSION_CLOSED,
            sessionId = sessionId,
            state = TerminalSessionState.CLOSED,
            message = "No live terminal session is open for this id; close was a benign, idempotent no-op.",
        )

    override fun shutdown() {
        // The foundation never opens sessions, so there is no registry to clear and
        // nothing can leak. Repeated or early shutdown is safe.
    }
}
