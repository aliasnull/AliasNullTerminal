package app.aliasnull.shell.terminal

/**
 * Opaque identity of one terminal-session engine session.
 *
 * This is a Kotlin-side engine identity only - NOT a process, a process handle or
 * a PID. No process exists behind it in this milestone, so it must never be named
 * or documented as a process id. Live engine session ids are expected to be
 * positive; [NO_SESSION] (the zero sentinel) never refers to a live session.
 *
 * The identity is independent of, and deliberately not interchangeable with:
 *
 *   - the Shell UI session id - the raw [Long] stored on the frontend
 *     TerminalSession that identifies a terminal tab/screen, and
 *   - a native foundation session id - the opaque [Long] reported by
 *     app.aliasnull.shell.runtime.native.NativeSessionResult for a native slot.
 *
 * The relationship is explicit and forward-only: once a real terminal engine
 * backend exists, one engine session may bind to at most one native foundation
 * slot and may be surfaced through one UI session. Until then this identity
 * exists purely as a contract-level handle and is never a running entity.
 */
@JvmInline
value class TerminalSessionId(val value: Long) {
    companion object {
        /** Sentinel meaning "no session"; never a live engine session id. */
        val NO_SESSION = TerminalSessionId(0L)
    }
}
