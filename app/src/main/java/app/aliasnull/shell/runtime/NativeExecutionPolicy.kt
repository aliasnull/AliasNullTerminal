package app.aliasnull.shell.runtime

import app.aliasnull.shell.bootstrap.BaseUserspaceArtifact
import app.aliasnull.shell.runtime.native.LaunchMode
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import java.io.File

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
 * The bundled AliasNull base-userspace executable is NOT part of that static
 * list, and it does not execve() directly: Android cannot execve() an ELF from
 * app-private storage (an app_data_file has no execute_no_trans right), so the
 * base executable's defined launch mode is [LaunchMode.LINKER_LAUNCH] - the
 * native runner execve()s [LINKER64_PATH], a system binary whose direct exec is
 * proven allowed, and the linker loads and runs the verified ELF as the child.
 * [baseExecutableInvocation] builds the exact LINKER_LAUNCH host argv from the
 * verified base directory, and [decideBaseExecutable] allows ONLY that one argv
 * - and only with [LaunchMode.LINKER_LAUNCH] declared - for ONLY the bundled
 * [BaseUserspaceArtifact.EXECUTABLE_FILE] when the caller supplies the verified
 * installed executable. The runtime derives that path from the verified
 * base-userspace bootstrap - never from UI input - so no other executable path,
 * linker host, argument list or launch mode can ever be selected through this
 * seam, and LINKER_LAUNCH never becomes a generic "run any file through the
 * linker" facility.
 *
 * An allowlisted argv must run exactly as listed: requests carrying a working
 * directory, environment overrides or a stdin payload are rejected even when
 * the argv matches, so no behaviour can be smuggled in beside the argv.
 */
object NativeExecutionPolicy {

    /**
     * The single argument `echo` is allowlisted to print. The value doubles as
     * the genuine message the Part 27-Q Echo self-check displays on stdout, so a
     * real child actually echoes this exact text back to the app.
     */
    const val SELFCHECK_STDOUT_TOKEN = "AliasNull native runtime OK"

    /** Missing-file argument for the stderr self-check; toybox `cat` reports it on stderr. */
    const val SELFCHECK_STDERR_PATH = "/no/such/aliasnull-p27-selfcheck-stderr-file"

    /** Deterministic non-existent executable for the LAUNCH_FAILED self-check. */
    const val SELFCHECK_MISSING_BINARY = "/system/bin/aliasnull-p27-no-such-selfcheck-binary"

    /**
     * The bundled base-userspace executable's deterministic single-line stdout
     * (Part 27-S2). This is the exact string the source-built
     * `aliasnull_base_probe` writes before exiting 0, so a real child process
     * genuinely produces this text.
     */
    const val BASE_USERSPACE_STDOUT_TOKEN = "AliasNull base userspace OK"

    /**
     * Canonical allowlisted invocations (Part 27-Q). Each is exactly one bare
     * argv that [decide] permits; naming them lets an internal diagnostic select
     * an authorized case without re-deriving argv lists and keeps [PERMITTED_ARGV]
     * built from the same single source.
     */
    val ECHO_INVOCATION: List<String> = listOf("/system/bin/echo", SELFCHECK_STDOUT_TOKEN)

    val STDERR_INVOCATION: List<String> = listOf("/system/bin/cat", SELFCHECK_STDERR_PATH)

    val NON_ZERO_EXIT_INVOCATION: List<String> = listOf("/system/bin/false")

    val LAUNCH_FAILURE_INVOCATION: List<String> = listOf(SELFCHECK_MISSING_BINARY)

    /**
     * The exact argv that runs the bundled AliasNull base-userspace executable
     * through the system dynamic linker (the defined [LaunchMode.LINKER_LAUNCH]
     * host argv): argv[0] is the fixed [LINKER64_PATH] and argv[1] is the
     * installed absolute path of the bundled executable under
     * [installedBaseUserspaceRoot]. [installedBaseUserspaceRoot] must be the
     * base-userspace directory the bootstrap verified (the runtime derives it
     * from [app.aliasnull.shell.bootstrap.BaseUserspaceBootstrap.installedCheck]);
     * it is never built from UI input. The executable runs with no arguments,
     * no shell, and no working directory/environment/stdin. A request built from
     * this argv must declare [LaunchMode.LINKER_LAUNCH].
     */
    fun baseExecutableInvocation(installedBaseUserspaceRoot: File): List<String> =
        listOf(
            LINKER64_PATH,
            File(installedBaseUserspaceRoot, BaseUserspaceArtifact.EXECUTABLE_FILE).absolutePath,
        )

    /**
     * The fixed system dynamic linker host used for the bundled base
     * executable's defined [LaunchMode.LINKER_LAUNCH] (Part 27-S2). It is the
     * arm64-v8a dynamic linker that every AArch64 system image ships at this
     * path; the bundled ELF's PT_INTERP is exactly this file, so running the
     * executable under it uses the same loader the kernel would use. The path is
     * fixed in policy, never read from the UI or any configuration, and [decide]
     * never permits exec'ing it as a DIRECT executable: the linker may appear in
     * an argv only as the argv[0] of a verified base-executable LINKER_LAUNCH
     * request approved by [decideBaseExecutable].
     */
    const val LINKER64_PATH = "/system/bin/linker64"

    /**
     * Decides the bundled base-executable request (Part 27-S2), the single
     * path-pinned [LaunchMode.LINKER_LAUNCH] allowance beside the static
     * [PERMITTED_ARGV] list.
     *
     * [installedBaseExecutable] is the installed bundled executable the caller
     * derived from the verified base directory. [process] is Allowed only when it
     * is a bare LINKER_LAUNCH argv (no working directory/environment/stdin) whose
     * argv is exactly `[LINKER64_PATH, <that executable's absolute path>]` AND
     * whose declared [NativeProcessRequest.launchMode] is
     * [LaunchMode.LINKER_LAUNCH] AND whose file name is the manifest's
     * allowlisted [BaseUserspaceArtifact.EXECUTABLE_FILE]. Every other shape - a
     * DIRECT request, a linker host other than [LINKER64_PATH], an arbitrary
     * target path, extra arguments, or an unexpected file name - is rejected
     * before it can reach the native runner. The ordinary [decide] never permits
     * this argv (it rejects any non-DIRECT request and any argv whose first
     * element is [LINKER64_PATH]), so only this explicit verified decision can
     * ever select the bundled binary through the linker.
     */
    fun decideBaseExecutable(
        process: NativeProcessRequest,
        installedBaseExecutable: File,
    ): NativeExecutionPolicyDecision {
        if (installedBaseExecutable.name != BaseUserspaceArtifact.EXECUTABLE_FILE) {
            return rejected(
                NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
                "The verified base executable must be the bundled " +
                    "'${BaseUserspaceArtifact.EXECUTABLE_FILE}', not '${installedBaseExecutable.name}'.",
            )
        }
        if (process.launchMode != LaunchMode.LINKER_LAUNCH) {
            return rejected(
                NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
                "The bundled base executable may not execve() directly on Android; " +
                    "its defined launch mode is LINKER_LAUNCH via $LINKER64_PATH.",
            )
        }
        val expected = listOf(LINKER64_PATH, installedBaseExecutable.absolutePath)
        if (process.argv != expected) {
            return rejected(
                NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
                "Only the bundled AliasNull base executable may run as the base-executable case, " +
                    "as $LINKER64_PATH with exactly the verified executable as its argument; " +
                    "got '${display(process.argv)}'.",
            )
        }
        if (process.workingDirectory != null || process.environment.isNotEmpty() ||
            process.stdinBytes != null
        ) {
            return rejected(
                NativeExecutionRejectionCode.ARGUMENTS_NOT_PERMITTED,
                "The bundled base executable LINKER_LAUNCH must run as a bare argv; " +
                    "no working directory, environment or stdin is permitted.",
            )
        }
        return NativeExecutionPolicyDecision.Allowed
    }

    /**
     * Decides whether [process] may reach the native runner. Total: every request
     * yields [Allowed] or [Rejected], never a throw.
     *
     * [process] is Allowed only as a bare [LaunchMode.DIRECT] request (no working
     * directory/environment/stdin) whose argv is one of [PERMITTED_ARGV]. Any
     * [LaunchMode.LINKER_LAUNCH] request - the base executable's defined mode - is
     * rejected here and may be approved only by [decideBaseExecutable], and
     * [LINKER64_PATH] is never allowed as a DIRECT executable, so no generic
     * "run this through the system linker" request can ever pass this gate.
     */
    fun decide(process: NativeProcessRequest): NativeExecutionPolicyDecision {
        val argv = process.argv
        if (argv.isEmpty() || argv[0].isEmpty()) {
            return rejected(
                NativeExecutionRejectionCode.INVALID_REQUEST,
                "A native execution request needs a non-empty executable name.",
            )
        }
        if (process.launchMode != LaunchMode.DIRECT) {
            return rejected(
                NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
                "Only DIRECT launches pass the ordinary policy; a LINKER_LAUNCH request " +
                    "is decided only for the single verified base executable by decideBaseExecutable.",
            )
        }
        if (argv[0] == LINKER64_PATH) {
            return rejected(
                NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
                "$LINKER64_PATH may not be exec'd directly; it runs only as the LINKER_LAUNCH " +
                    "host of the verified base executable.",
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
        ECHO_INVOCATION,
        STDERR_INVOCATION,
        NON_ZERO_EXIT_INVOCATION,
        LAUNCH_FAILURE_INVOCATION,
    )
}
