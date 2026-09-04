package app.aliasnull.shell.runtime

/**
 * The Shell's single, unambiguous command-backend phase -- the gate that decides
 * whether the interactive Shell may accept commands.
 *
 * This is deliberately a *derived* view of the runtime's own initialization, not
 * a second readiness state machine: READY is only ever reported once the AN Shell
 * core bridge genuinely verifies (the one authoritative readiness fact,
 * AnShellCoreBridge.currentStatus surfaced through backend availability) AND the
 * AliasNull base userspace is installed and verified (Part 27-R); FAILED is
 * reported only when a real initialization/verification attempt has completed
 * without a READY core or without a verified base userspace; and INITIALIZING
 * covers "an attempt is running or none has finished". No timer and no UI code
 * fabricates a transition.
 */
enum class ShellBackendPhase {
    /** An initialization/verification attempt is running, or none has completed yet. */
    INITIALIZING,

    /**
     * The AN Shell core bridge is genuinely READY AND the AliasNull base
     * userspace is installed and verified; the Shell may execute commands.
     */
    READY,

    /**
     * The most recent attempt completed without a READY core or without a
     * verified base userspace; Retry may re-run it.
     */
    FAILED,
}

/**
 * One gate value: the unambiguous [phase] plus an optional user-safe reason
 * carried only when the phase is [ShellBackendPhase.FAILED]. A single value --
 * never a set of parallel booleans -- so the UI has exactly one state at a time.
 */
data class ShellBackendState(
    val phase: ShellBackendPhase,
    val failureMessage: String? = null,
) {
    companion object {
        /** The truthful pre-verification default: nothing is ready yet. */
        val INITIALIZING = ShellBackendState(ShellBackendPhase.INITIALIZING)

        /** Reported only once the AN core verifies AND the base userspace is verified. */
        val READY = ShellBackendState(ShellBackendPhase.READY)

        /** Reported only when a real attempt lacked a READY core or base userspace. */
        fun failed(message: String) = ShellBackendState(ShellBackendPhase.FAILED, message)
    }
}
