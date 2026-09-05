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
 * base-userspace executable cases map to the single verified installed bundled
 * executable - [BASE_USERSPACE_EXECUTABLE] as its bare
 * [LaunchMode.LINKER_LAUNCH] argv via [NativeExecutionPolicy.decideBaseExecutable],
 * [BASE_EXECUTION_ENVIRONMENT] under the controlled
 * [BaseExecutionEnvironment] via [NativeExecutionPolicy.decideBaseExecutionEnvironment],
 * and [BASE_DIGEST] under the controlled [BaseDigestEnvironment] via
 * [NativeExecutionPolicy.decideBaseDigest] -
 * so a case can never smuggle an arbitrary request past the policy gate. The UI
 * labels come from [title]; the request is built internally ([request]).
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
    BASE_USERSPACE_EXECUTABLE("Base Executable"),

    /**
     * The controlled base-execution-environment case (Part 27-T1): the SAME
     * bundled executable as [BASE_USERSPACE_EXECUTABLE], launched under the one
     * execution environment [BaseExecutionEnvironment] established from the
     * verified base userspace - the controlled working directory and the one
     * fixed AliasNull-owned environment override
     * ([NativeExecutionPolicy.baseExecutionEnvironmentOverrides]). Its request is
     * built by [request] from that model (never from UI input), and the policy
     * gate is [NativeExecutionPolicy.decideBaseExecutionEnvironment], so no
     * user-supplied cwd, variable, target or argument can ever be selected. The
     * probe then reports its real working directory and the override, proving the
     * environment was actually applied.
     */
    BASE_EXECUTION_ENVIRONMENT("Base Environment"),

    /**
     * The controlled base-digest case (Part 27-T2): the bundled AliasNull
     * base-userspace SHA-256 file-digest component launched under the one
     * execution environment [BaseDigestEnvironment] established from the verified
     * base userspace - the controlled working directory and the one fixed
     * AliasNull-owned environment override
     * ([NativeExecutionPolicy.baseDigestEnvironmentOverrides]) naming the verified
     * installed base root. Its request is built by [request] from that model
     * (never from UI input), and the policy gate is
     * [NativeExecutionPolicy.decideBaseDigest], so no user-supplied root, cwd,
     * variable, target or argument can ever be selected. The component then
     * hashes exactly the installed base files and prints one deterministic digest
     * line per file; [NativeProcessTestKind.matches] validates that output
     * strictly against [app.aliasnull.shell.bootstrap.BaseUserspaceArtifact.FILES],
     * so a real userspace executable must independently reproduce the manifest
     * digests for the installed base to pass.
     */
    BASE_DIGEST("Base Digest");

    /**
     * True only for the bundled base-executable cases (S2/T1/T2), whose argv is
     * pinned to the verified installed executable path and therefore cannot be a
     * bare policy invocation.
     */
    val isBundledBaseExecutable: Boolean
        get() = this == BASE_USERSPACE_EXECUTABLE ||
            this == BASE_EXECUTION_ENVIRONMENT ||
            this == BASE_DIGEST

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
            BASE_EXECUTION_ENVIRONMENT -> throw IllegalStateException(
                "The controlled base-execution-environment argv depends on the verified base " +
                    "userspace and its working directory; use request(environment) instead.",
            )
            BASE_DIGEST -> throw IllegalStateException(
                "The controlled base-digest argv depends on the verified base userspace, its " +
                    "working directory and its installed root; use request(digestEnvironment) instead.",
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
        require(this == BASE_USERSPACE_EXECUTABLE) {
            "request(installedBaseUserspaceRoot) builds the bare base-executable LINKER_LAUNCH " +
                "request; the base-execution-environment case uses request(environment) instead."
        }
        return NativeProcessRequest(
            argv = NativeExecutionPolicy.baseExecutableInvocation(installedBaseUserspaceRoot),
            launchMode = LaunchMode.LINKER_LAUNCH,
        )
    }

    /**
     * The one structured request the controlled base-execution-environment case
     * authorizes (Part 27-T1): the SAME bundled-executable LINKER_LAUNCH argv as
     * the [BASE_USERSPACE_EXECUTABLE] case, but under the [environment] the
     * execution-environment layer established from the verified base userspace -
     * its controlled working directory ([BaseExecutionEnvironment.workingDirectoryPath])
     * and its one fixed environment override ([BaseExecutionEnvironment.variables]).
     * [environment] is never UI input, and the policy gate re-checks the exact
     * executable File name, argv, launch mode, working directory and environment
     * before the runner runs, so the controlled cwd/env can never be smuggled onto
     * any other argv.
     */
    internal fun request(environment: BaseExecutionEnvironment): NativeProcessRequest {
        require(this == BASE_EXECUTION_ENVIRONMENT) {
            "request(environment) is only valid for the controlled base-execution-environment case."
        }
        return NativeProcessRequest(
            argv = NativeExecutionPolicy.baseExecutableInvocation(environment.installedRoot),
            launchMode = LaunchMode.LINKER_LAUNCH,
            workingDirectory = environment.workingDirectoryPath,
            environment = environment.variables,
        )
    }

    /**
     * The one structured request the controlled base-digest case authorizes
     * (Part 27-T2): the bundled digest component's LINKER_LAUNCH argv
     * ([NativeExecutionPolicy.baseDigestInvocation]) under the [environment] the
     * digest-environment layer established from the verified base userspace - its
     * controlled working directory ([BaseDigestEnvironment.workingDirectoryPath])
     * and its one fixed environment override ([BaseDigestEnvironment.variables],
     * which names the verified installed root as the digest's controlled root).
     * [environment] is never UI input, and the policy gate re-checks the exact
     * executable File name, argv, launch mode, working directory and environment
     * before the runner runs, so the controlled root and cwd can never be smuggled
     * onto any other argv.
     */
    internal fun request(environment: BaseDigestEnvironment): NativeProcessRequest {
        require(this == BASE_DIGEST) {
            "request(digestEnvironment) is only valid for the controlled base-digest case."
        }
        return NativeProcessRequest(
            argv = NativeExecutionPolicy.baseDigestInvocation(environment.installedRoot),
            launchMode = LaunchMode.LINKER_LAUNCH,
            workingDirectory = environment.workingDirectoryPath,
            environment = environment.variables,
        )
    }

    /**
     * True only when [result] (the genuine native outcome the runner returned for
     * this case's argv) satisfies the case's stated expectation. For the three
     * launch cases this means EXITED with the expected streams/exit; for
     * LAUNCH_FAILURE it means the executable genuinely could not be started. The
     * [BASE_EXECUTION_ENVIRONMENT] case cannot be judged without the controlled
     * [BaseExecutionEnvironment] it ran under, so this form throws for it; use
     * [matches] with that environment instead.
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

        BASE_EXECUTION_ENVIRONMENT -> throw IllegalStateException(
            "The controlled base-execution-environment expectation needs the controlled " +
                "environment it ran under; use matches(result, environment).",
        )
        BASE_DIGEST -> throw IllegalStateException(
            "The controlled base-digest expectation needs the controlled digest environment " +
                "it ran under; use matches(result, digestEnvironment).",
        )
    }

    /**
     * True only when [result] satisfies the controlled base-execution-environment
     * case's stated expectation (Part 27-T1), judged against the genuine
     * [environment] the request was executed under: the probe EXITED 0, printed
     * the fixed controlled-env header token, echoed the real working directory
     * ([BaseExecutionEnvironment.workingDirectoryPath] - proving the child
     * actually chdir'd into the controlled directory) and the one AliasNull-owned
     * environment override ([environment.variables] echoed back), and wrote
     * nothing to stderr. Every value compared comes from the verified
     * [environment] model and the fixed policy constants, never from UI input.
     */
    internal fun matches(result: NativeProcessResult, environment: BaseExecutionEnvironment): Boolean {
        require(this == BASE_EXECUTION_ENVIRONMENT) {
            "matches(result, environment) is only valid for the controlled " +
                "base-execution-environment case."
        }
        val overrides = environment.variables
        val envLine = overrides.entries.joinToString(prefix = "", postfix = "") { (key, value) ->
            "$key=$value"
        }
        return result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode == 0 &&
            result.stdout.contains(NativeExecutionPolicy.BASE_ENVIRONMENT_STDOUT_TOKEN) &&
            result.stdout.contains("cwd=${environment.workingDirectoryPath}") &&
            result.stdout.contains(envLine) &&
            result.stderr.isEmpty()
    }

    /**
     * True only when [result] satisfies the controlled base-digest case's stated
     * expectation (Part 27-T2), judged against the genuine [environment] the
     * request was executed under: the digest component EXITED 0, wrote nothing to
     * stderr, and printed exactly one deterministic `<sha256>  <name>` line per
     * installed base file - the same files, in the same order, as
     * [BaseUserspaceArtifact.FILES] - with every digest equal to that manifest's
     * expected value ([BaseDigestOutputValidator.baseDigestExpected]). A real
     * userspace executable therefore must independently reproduce the bootstrap's
     * manifest digests for the whole installed base; any deviation (missing,
     * extra, duplicate, reordered or mismatched line, non-zero exit, stderr
     * output) makes [expectedMet] false.
     */
    internal fun matches(result: NativeProcessResult, environment: BaseDigestEnvironment): Boolean {
        require(this == BASE_DIGEST) {
            "matches(result, digestEnvironment) is only valid for the controlled base-digest case."
        }
        return result.outcome == NativeProcessOutcome.EXITED &&
            result.exitCode == 0 &&
            result.stderr.isEmpty() &&
            BaseDigestOutputValidator.validate(
                BaseDigestOutputValidator.baseDigestExpected,
                result.stdout,
            ).valid
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
            BASE_EXECUTION_ENVIRONMENT ->
                "expected: exit 0, '${NativeExecutionPolicy.BASE_ENVIRONMENT_STDOUT_TOKEN}', the " +
                    "controlled working directory and '${NativeExecutionPolicy.BASE_ENVIRONMENT_VAR}=...' " +
                    "on stdout, nothing on stderr"
            BASE_DIGEST ->
                "expected: exit 0, one 'sha256  name' digest line per installed base file " +
                    "matching the base manifest (in order), nothing on stderr"
        }
}
