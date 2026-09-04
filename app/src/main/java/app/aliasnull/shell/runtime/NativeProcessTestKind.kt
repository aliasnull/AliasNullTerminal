package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import app.aliasnull.shell.runtime.native.LaunchMode
import app.aliasnull.shell.runtime.native.NativeProcessOutcome
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import app.aliasnull.shell.runtime.native.NativeProcessResult
import java.io.File

/**
 * The authorized native-process self-check cases a Part 27-Q diagnostic surface
 * may run.
 *
 * This enum is the ONLY choice a caller (a ViewModel, a button) may make. There
 * is deliberately no way to pick an executable, an argument list, a working
 * directory, environment or stdin here: every host case maps to exactly one
 * allowlisted bare-argv invocation owned by [NativeExecutionPolicy]
 * ([NativeExecutionPolicy.ECHO_INVOCATION] and friends), and the bundled
 * base-userspace executable case
 * ([BASE_USERSPACE_EXECUTABLE]) maps to the single verified installed bundled
 * executable via [NativeExecutionPolicy.decideBaseExecutable], so a case can
 * never smuggle an arbitrary request past the policy gate. The UI labels come
 * from [title]; the request is built internally ([request]).
 */
enum class NativeProcessTestKind(val title: String) {
    /** `/system/bin/echo <token>`: a genuine successful launch writing to stdout. */
    ECHO("Echo"),

    /** `/system/bin/cat <missing-file>`: a real non-zero exit with genuine stderr. */
    STDERR("Stderr"),

    /** `/system/bin/false`: a genuine non-zero exit with empty output. */
    NON_ZERO_EXIT("Non-zero Exit"),

    /** A fixed never-present binary: the launch genuinely fails. */
    LAUNCH_FAILURE("Launch Failure"),

    /**
     * The bundled AliasNull base-userspace executable (Part 27-S2): the real
     * 64-bit AArch64 executable the base bootstrap installed and verified. Its
     * defined launch mode is [LaunchMode.LINKER_LAUNCH], so the native runner
     * execve()s the system dynamic linker with the executable's single installed
     * absolute path as its argument; the real ELF then runs bare and prints
     * "AliasNull base userspace OK" then exits 0.
     */
    BASE_USERSPACE_EXECUTABLE("Base Executable");

    /** True only for the bundled base-executable case, whose argv is path-pinned. */
    val isBundledBaseExecutable: Boolean
        get() = this == BASE_USERSPACE_EXECUTABLE

    /**
     * The exact allowlisted argv for this case, read from the single policy
     * source. The policy remains authoritative: the request built from this argv
     * must still be [NativeExecutionPolicyDecision.Allowed] before it runs. For
     * [BASE_USERSPACE_EXECUTABLE] the argv depends on the verified install path,
     * so this is not used; build it with [request] passing the installed root.
     */
    val invocation: List<String>
        get() = when (this) {
            ECHO -> NativeExecutionPolicy.ECHO_INVOCATION
            STDERR -> NativeExecutionPolicy.STDERR_INVOCATION
            NON_ZERO_EXIT -> NativeExecutionPolicy.NON_ZERO_EXIT_INVOCATION
            LAUNCH_FAILURE -> NativeExecutionPolicy.LAUNCH_FAILURE_INVOCATION
            BASE_USERSPACE_EXECUTABLE -> throw IllegalStateException(
                "The bundled base executable argv depends on its verified install path; " +
                    "use request(installedBaseUserspaceRoot) instead.",
            )
        }

    /** The one structured request this case authorizes. Internal to the runtime. */
    internal fun request(): NativeProcessRequest = NativeProcessRequest(argv = invocation)

    /**
     * The one structured request the bundled base-executable case authorizes:
     * its defined [LaunchMode.LINKER_LAUNCH] request - argv is the fixed system
     * linker host [NativeExecutionPolicy.LINKER64_PATH] with the bundled
     * [BaseUserspaceArtifact.EXECUTABLE_FILE] under the verified
     * [installedBaseUserspaceRoot] as its single argument, and the launch mode
     * declared LINKER_LAUNCH. The root is the base-userspace directory the
     * bootstrap verified; it is never UI input, and the policy re-checks the exact
     * path and mode before the runner runs.
     */
    internal fun request(installedBaseUserspaceRoot: File): NativeProcessRequest {
        require(isBundledBaseExecutable) {
            "request(installedBaseUserspaceRoot) is only valid for the bundled base-executable case."
        }
        return NativeProcessRequest(
            argv = NativeExecutionPolicy.baseExecutableInvocation(installedBaseUserspaceRoot),
            launchMode = LaunchMode.LINKER_LAUNCH,
        )
    }

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

        BASE_USERSPACE_EXECUTABLE -> result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode == 0 &&
            result.stdout.contains(NativeExecutionPolicy.BASE_USERSPACE_STDOUT_TOKEN) &&
            result.stderr.isEmpty()
    }

    /** Short, honest description of what [matches] checks; shown next to a result. */
    val expectationText: String
        get() = when (this) {
            ECHO -> "expected: exit 0, the message on stdout, nothing on stderr"
            STDERR -> "expected: non-zero exit, nothing on stdout, a real error on stderr"
            NON_ZERO_EXIT -> "expected: exit 1 with empty stdout and stderr"
            LAUNCH_FAILURE -> "expected: the executable is not found, so the launch fails"
            BASE_USERSPACE_EXECUTABLE ->
                "expected: exit 0, '${NativeExecutionPolicy.BASE_USERSPACE_STDOUT_TOKEN}' on stdout, nothing on stderr"
        }
}
