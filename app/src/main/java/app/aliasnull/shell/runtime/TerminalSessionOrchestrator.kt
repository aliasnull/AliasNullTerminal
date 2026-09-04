package app.aliasnull.shell.runtime

import app.aliasnull.shell.terminal.TerminalEngineAvailability
import app.aliasnull.shell.terminal.TerminalSessionEngine
import app.aliasnull.shell.terminal.TerminalSessionEngineFoundation
import app.aliasnull.shell.terminal.TerminalSessionId
import app.aliasnull.shell.terminal.TerminalSessionResult

/**
 * The single terminal-session orchestration boundary: the one place a Shell UI
 * session asks the [TerminalSessionEngine] for an engine session and, later,
 * releases one. It coordinates, it does not execute.
 *
 * The relationship it coordinates is the one Part 26-M reconciled: a UI session
 * (raw [Long] id, owned by [app.aliasnull.ui.screens.shell.ShellViewModel]) may
 * carry zero or one optional engine session ([TerminalSessionId], owned by the
 * engine). This boundary exists so that future UI code requests that engine
 * session through one owned place instead of reaching into the engine directly
 * and dropping the association. It must NOT become a replacement ShellViewModel.
 *
 * Honest present-day behavior: the engine hosted by the current runtime is the
 * contract-only foundation (see [TerminalSessionEngineFoundation]), which cannot
 * open a session. Every request therefore resolves to the engine's own
 * "backend unavailable" result and no engine session is ever attached. Nothing
 * here fabricates a session, a READY/ACTIVE/STARTING/CLOSING state, output, a
 * prompt, or process activity.
 *
 * Boundaries this type deliberately does NOT own:
 *
 *   - Compose/UI state, UI tab creation/selection, command history, keyboard
 *     state, or the Shell's in-flight execution jobs - those stay with the
 *     ShellViewModel / Shell UI.
 *   - Command routing - it is not a ShellCommandExecutor, never receives a
 *     ShellExecutionRequest, and never participates in ExecutionRouter decisions.
 *   - Engine lifecycle ownership - the runtime manager owns the engine and its
 *     deterministic shutdown (Part 26-L); this coordinator owns no live
 *     resources, so it has no shutdown of its own and never calls
 *     [TerminalSessionEngine.shutdown].
 *   - JNI, native session-slot ownership, or process spawning.
 *
 * A UI session may legitimately exist with no engine session: absence is the
 * normal, honest state until a real backend exists.
 */
interface TerminalSessionOrchestrator {

    /**
     * Whether an engine session could be opened and attached right now. This is
     * the engine's own availability (no duplicated vocabulary): today
     * [TerminalEngineAvailability.canHostTerminalSession] is false because the
     * hosted engine has no session backend.
     */
    val availability: TerminalEngineAvailability

    /**
     * Requests, on behalf of the UI session identified by [uiSessionId], that an
     * engine session be opened and made available for association.
     *
     * [uiSessionId] is a caller-supplied correlation token only. It is not
     * converted into a [TerminalSessionId], is not stored, and is not the basis
     * of any registry: this coordinator is stateless. The request is forwarded to
     * the hosted engine's own open operation and the engine's [TerminalSessionResult]
     * is returned unchanged, so the outcome vocabulary is exactly the engine's:
     *
     *   - today: [app.aliasnull.shell.terminal.TerminalSessionOutcome.ENGINE_UNAVAILABLE]
     *     with [TerminalSessionId.NO_SESSION] - no engine session was created.
     *   - a future real backend: SESSION_OPENED with a genuine [TerminalSessionId];
     *     the caller (the UI session owner) is then responsible for associating
     *     that id, e.g. into its [app.aliasnull.ui.screens.shell.TerminalSession.engineSessionId].
     *
     * A failure result never carries a fabricated [TerminalSessionId].
     */
    fun requestSessionForUiSession(uiSessionId: Long): TerminalSessionResult

    /**
     * Requests that the engine session identified by [engineSessionId] be closed,
     * on behalf of the UI session identified by [uiSessionId].
     *
     * The caller invokes this only when its UI session holds a genuine engine
     * association (a non-null [TerminalSessionId]); when a UI session has no
     * engine session, no engine close is required and this is not called. Closing
     * delegates to the hosted engine's idempotent close operation, so repeated
     * close, an unknown id, or an already-closed id is a benign no-op and never
     * crashes. [uiSessionId] is again only correlation context; it is not stored
     * and not converted.
     */
    fun closeSessionForUiSession(uiSessionId: Long, engineSessionId: TerminalSessionId): TerminalSessionResult
}

/**
 * Contract-only orchestration foundation: the honest runtime answer while the
 * hosted terminal engine has no session backend.
 *
 * It coordinates exactly the engine the current runtimes host -
 * [TerminalSessionEngineFoundation] - so it is honest by construction:
 *
 *   - [availability] mirrors the engine's contract-present / no-session-backend
 *     state ([TerminalEngineAvailability.canHostTerminalSession] is false);
 *   - [requestSessionForUiSession] returns the engine's
 *     [app.aliasnull.shell.terminal.TerminalSessionOutcome.ENGINE_UNAVAILABLE]
 *     result unchanged (nothing opened, nothing reserved);
 *   - [closeSessionForUiSession] forwards to the engine's idempotent close, which
 *     for an id that was never opened is a benign no-op - it never pretends a
 *     live session existed.
 *
 * The coordinator is stateless: it keeps no UI-session registry, holds no live
 * engine session, and therefore owns nothing to release on runtime shutdown -
 * engine cleanup stays with the runtime manager (Part 26-L), not here.
 */
object TerminalSessionOrchestratorFoundation : TerminalSessionOrchestrator {

    override val availability: TerminalEngineAvailability
        get() = TerminalSessionEngineFoundation.availability

    override fun requestSessionForUiSession(uiSessionId: Long): TerminalSessionResult =
        // The foundation engine opens no session; the UI-session token is not
        // needed because there is nothing to coordinate against today.
        TerminalSessionEngineFoundation.openSession()

    override fun closeSessionForUiSession(uiSessionId: Long, engineSessionId: TerminalSessionId): TerminalSessionResult =
        TerminalSessionEngineFoundation.closeSession(engineSessionId)
}
