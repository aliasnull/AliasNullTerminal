package app.aliasnull.shell.runtime

import app.aliasnull.shell.runtime.native.NativeProcessOutcome
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import app.aliasnull.shell.runtime.native.NativeProcessResult

/**
 * The authorized native-process self-check cases a Part 27-Q diagnostic surface
 * may run.
 *
 * This enum is the ONLY choice a caller (a ViewModel, a button) may make. There
 * is deliberately no way to pick an executable, an argument list, a working
 * directory, environment or stdin here: every case maps to exactly one
 * allowlisted bare-argv invocation owned by [NativeExecutionPolicy]
 * ([NativeExecutionPolicy.ECHO_INVOCATION] and friends), so a case can never
 * smuggle an arbitrary request past the policy gate. The UI labels come from
 * [title]; the request is built internally ([request]).
 */
enum class NativeProcessTestKind(val title: String) {
    /** `/system/bin/echo <token>`: a genuine successful launch writing to stdout. */
    ECHO("Echo"),

    /** `/system/bin/cat <missing-file>`: a real non-zero exit with genuine stderr. */
    STDERR("Stderr"),

    /** `/system/bin/false`: a genuine non-zero exit with empty output. */
    NON_ZERO_EXIT("Non-zero Exit"),

    /** A fixed never-present binary: the launch genuinely fails. */
    LAUNCH_FAILURE("Launch Failure");

    /**
     * The exact allowlisted argv for this case, read from the single policy
     * source. The policy remains authoritative: the request built from this argv
     * must still be [NativeExecutionPolicyDecision.Allowed] before it runs.
     */
    val invocation: List<String>
        get() = when (this) {
            ECHO -> NativeExecutionPolicy.ECHO_INVOCATION
            STDERR -> NativeExecutionPolicy.STDERR_INVOCATION
            NON_ZERO_EXIT -> NativeExecutionPolicy.NON_ZERO_EXIT_INVOCATION
            LAUNCH_FAILURE -> NativeExecutionPolicy.LAUNCH_FAILURE_INVOCATION
        }

    /** The one structured request this case authorizes. Internal to the runtime. */
    internal fun request(): NativeProcessRequest = NativeProcessRequest(argv = invocation)

    /**
     * True only when [result] (the genuine native outcome the runner returned for
     * this case's argv) satisfies the case's stated expectation. For the three
     * launch cases this means EXITED with the expected streams/exit; for
     * LAUNCH_FAILURE it means the executable genuinely could not be started.
     */
    fun matches(result: NativeProcessResult): Boolean = when (this) {
        ECHO -> result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode == 0 &&
            result.stdout.contains(NativeExecutionPolicy.SELFCHECK_STDOUT_TOKEN) &&
            result.stderr.isEmpty()

        STDERR -> result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode != null && result.exitCode != 0 &&
            result.stdout.isEmpty() &&
            result.stderr.isNotEmpty()

        NON_ZERO_EXIT -> result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode == 1 &&
            result.stdout.isEmpty() &&
            result.stderr.isEmpty()

        LAUNCH_FAILURE -> result.outcome == NativeProcessOutcome.LAUNCH_FAILED &&
            !result.processRan
    }

    /** Short, honest description of what [matches] checks; shown next to a result. */
    val expectationText: String
        get() = when (this) {
            ECHO -> "expected: exit 0, the message on stdout, nothing on stderr"
            STDERR -> "expected: non-zero exit, nothing on stdout, a real error on stderr"
            NON_ZERO_EXIT -> "expected: exit 1 with empty stdout and stderr"
            LAUNCH_FAILURE -> "expected: the executable is not found, so the launch fails"
        }
}
