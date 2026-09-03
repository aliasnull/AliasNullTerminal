package app.aliasnull.shell.runtime.native

/**
 * Lifecycle state of a native "session slot" - an opaque placeholder identity for
 * a future execution backend. The state vocabulary mirrors the C++ side (see
 * aliasnull_runtime.cpp). Only [READY] and [CLOSED] are reachable in this phase:
 * a created slot is READY and, when closed, retires (the id becomes invalid).
 * [STARTING] and [RUNNING] are reserved for the future real process/PTY phase and
 * are never reported by this foundation, which spawns nothing.
 */
enum class NativeSessionState {
    /** Conceptual default for a slot that has never been created. */
    UNINITIALIZED,

    /** Created and validated; awaiting a future execution binding. Nothing is running. */
    READY,

    /** Reserved future transition before a real process starts. Never set by this foundation. */
    STARTING,

    /** Reserved: a real process is running. Never set by this foundation. */
    RUNNING,

    /** Terminal state: the slot has been closed and retired. */
    CLOSED,

    /** The native layer reported an error for this session. */
    ERROR,
}

/**
 * Honest classification of one native session operation, so a caller can tell
 * exactly why a slot is not available instead of collapsing every case into one
 * boolean. The distinctions required by the session foundation are present:
 *
 *   LIBRARY_UNAVAILABLE - the .so could not be loaded (no native layer at all).
 *   BOOTSTRAP_NOT_READY - the library is loaded but native bootstrap never
 *                         completed (or was released), so no slot can exist.
 *   SESSION_PREP_FAILED - bootstrap succeeded but the native layer could not
 *                         reserve a slot (internal).
 *   SESSION_READY       - a slot was created; [sessionId] is valid and stable.
 *   SESSION_CLOSED      - a slot was closed, or close was a benign no-op.
 *   INVALID_SESSION_ID  - the supplied id is not a live session.
 *   SESSION_LAYER_STOPPED / UNEXPECTED - infrastructure edge cases.
 */
enum class NativeSessionOutcome {
    SESSION_READY,
    SESSION_CLOSED,
    INVALID_SESSION_ID,
    LIBRARY_UNAVAILABLE,
    BOOTSTRAP_NOT_READY,
    SESSION_PREP_FAILED,
    SESSION_LAYER_STOPPED,
    UNEXPECTED,
}

/**
 * Structured, honest outcome of one native session-slot operation. [sessionId]
 * is [NO_SESSION] unless the operation produced or targeted a real slot.
 */
data class NativeSessionResult(
    val outcome: NativeSessionOutcome,
    val sessionId: Long = NO_SESSION,
    val state: NativeSessionState? = null,
    val message: String = "",
) {
    val success: Boolean
        get() = outcome == NativeSessionOutcome.SESSION_READY ||
            outcome == NativeSessionOutcome.SESSION_CLOSED

    companion object {
        /** Sentinel meaning "no session": never a valid native slot id (native ids start at 1). */
        const val NO_SESSION = 0L

        fun unexpected(error: Throwable) = NativeSessionResult(
            outcome = NativeSessionOutcome.UNEXPECTED,
            message = "Native session operation error: ${error.message ?: error::class.simpleName ?: "unknown"}",
        )
    }
}
