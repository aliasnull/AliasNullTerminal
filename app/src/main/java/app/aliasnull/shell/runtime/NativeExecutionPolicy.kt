package app.aliasnull.shell.runtime

import app.aliasnull.shell.runtime.native.NativeProcessRequest

/**
 * Stable, machine-readable reason a structured native-execution request was
 * refused by [NativeExecutionPolicy] before it could reach the native runner.
 *
 * Policy rejection is its own layer and must never be confused with a native
 * process outcome. A rejected request never reaches
 * [app.aliasnull.shell.runtime.native.AliasNullNativeRuntime.runProcess], so it
 * can never produce EXITED, TERMINATED_BY_SIGNAL, LAUNCH_FAILED or
 * INTERNAL_ERROR. Those values describe what happened *after* a request reached
 * the runner; this enum describes why a request was *not permitted* to reach
 * it. Keeping the layers separate means a future UI/router can handle policy
 * rejection and process outcomes without parsing strings.
 */
enum class NativeExecutionRejectionCode {
    /** The request is malformed (empty executable), not merely not permitted. */
    INVALID_REQUEST,

    /** argv[0] is not an executable this policy permits for internal execution. */
    EXECUTABLE_NOT_PERMITTED,

    /** argv[0] is permitted but the exact invocation (arguments or shape) is not allowlisted. */
    ARGUMENTS_NOT_PERMITTED,
}

/** One policy refusal: a stable machine-readable [code] plus a diagnostic [message]. */
data class NativeExecutionRejection(
    val code: NativeExecutionRejectionCode,
    val message: String,
)

/**
 * Decision of [NativeExecutionPolicy] for one structured execution request.
 * [Allowed] is the only decision that may proceed toward the native runner.
 */
sealed interface NativeExecutionPolicyDecision {
    /** The request matches the internal allowlist and may reach the native runner. */
    data object Allowed : NativeExecutionPolicyDecision

    /** The request was refused before native execution. No child process is launched. */
    data class Rejected(val reason: NativeExecutionRejection) : NativeExecutionPolicyDecision
}

/**
 * The narrow, explicit execution policy for the controlled native process seam
 * (Part 27-P).
 *
 * The policy gates *structured argv requests*, never shell command strings. It
 * decides purely on the request shape and is deterministic and inspectable: for
 * any request a caller can state exactly why it was allowed (its argv is one of
 * [permittedInvocations]) or rejected (which [NativeExecutionRejectionCode]
 * applied), and a rejected request is never forwarded to native code.
 *
 * Today the ONLY permitted invocations are the fixed internal self-verification
 * argv sequences ([permittedInvocations]) that run real, universally present
 * Android host binaries (`/system/bin/echo`, `/system/bin/cat`,
 * `/system/bin/false` from toybox) plus one explicit allowlisted negative case
 * that exercises the native LAUNCH_FAILED path. These are internal diagnostics
 * and are never user-facing Shell commands. A future internal consumer must
 * extend the allowlist deliberately; no arbitrary user-entered argv is ever
 * permitted, and in particular nothing routes AN Shell unknown commands here.
 *
 * An allowlisted argv must run exactly as listed: requests carrying a working
 * directory, environment overrides or a stdin payload are rejected even when
 * the argv matches, so no behaviour can be smuggled in beside the argv.
 */
object NativeExecutionPolicy {

    /** Executable used by the stdout self-check; resolves to the real toybox `echo`. */
    const val SELFCHECK_STDOUT_TOKEN = "aliasnull-p27-selfcheck-stdout"

    /** Missing-file argument for the stderr self-check; toybox `cat` reports it on stderr. */
    const val SELFCHECK_STDERR_PATH = "/no/such/aliasnull-p27-selfcheck-stderr-file"

    /** Deterministic non-existent executable for the LAUNCH_FAILED self-check. */
    const val SELFCHECK_MISSING_BINARY = "/system/bin/aliasnull-p27-no-such-selfcheck-binary"

    /**
     * Decides whether [process] may reach the native runner. Total: every request
     * yields [Allowed] or [Rejected], never a throw.
     */
    fun decide(process: NativeProcessRequest): NativeExecutionPolicyDecision {
        val argv = process.argv
        if (argv.isEmpty() || argv[0].isEmpty()) {
            return rejected(
                NativeExecutionRejectionCode.INVALID_REQUEST,
                "A native execution request needs a non-empty executable name.",
            )
        }
        val executableAllowed = PERMITTED_ARGV.any { it.first() == argv[0] }
        if (argv !in PERMITTED_ARGV) {
            return rejected(
                if (executableAllowed) {
                    NativeExecutionRejectionCode.ARGUMENTS_NOT_PERMITTED
                } else {
                    NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED
                },
                rejectionMessage(executableAllowed, argv),
            )
        }
        if (process.workingDirectory != null || process.environment.isNotEmpty() ||
            process.stdinBytes != null
        ) {
            return rejected(
                NativeExecutionRejectionCode.ARGUMENTS_NOT_PERMITTED,
                "The allowlisted invocation '${display(argv)}' must run as a bare argv; " +
                    "no working directory, environment or stdin is permitted.",
            )
        }
        return NativeExecutionPolicyDecision.Allowed
    }

    /**
     * Inspectable snapshot of exactly which invocations are permitted today, in
     * stable order. Deterministic; used by the internal self-check and available
     * so the allow decision is never opaque.
     */
    val permittedInvocations: List<List<String>>
        get() = PERMITTED_ARGV

    private fun rejectionMessage(executableAllowed: Boolean, argv: List<String>): String =
        if (executableAllowed) {
            "The arguments in '${display(argv)}' are not an allowlisted internal invocation."
        } else {
            "Executable '${argv[0]}' is not permitted for internal native execution."
        }

    private fun rejected(code: NativeExecutionRejectionCode, message: String) =
        NativeExecutionPolicyDecision.Rejected(NativeExecutionRejection(code, message))

    private fun display(argv: List<String>): String = argv.joinToString(" ")

    private val PERMITTED_ARGV: List<List<String>> = listOf(
        // Genuine successful launch + stdout capture (exit 0).
        listOf("/system/bin/echo", SELFCHECK_STDOUT_TOKEN),
        // Genuine stderr capture and a real non-zero exit (toybox cat reports a
        // missing file on stderr and exits 1; stdout must stay empty).
        listOf("/system/bin/cat", SELFCHECK_STDERR_PATH),
        // Real non-zero exit with no output.
        listOf("/system/bin/false"),
        // Explicit internal negative case, allowlisted so the native
        // LAUNCH_FAILED path is reachable through the same seam. The path is
        // fixed and never exists.
        listOf(SELFCHECK_MISSING_BINARY),
    )
}
