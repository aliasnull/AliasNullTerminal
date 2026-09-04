package app.aliasnull.shell.runtime

import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.NativeProcessOutcome
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import app.aliasnull.shell.runtime.native.NativeProcessResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * One executed case in a [NativeProcessSelfCheckReport]. [expectedMet] is true
 * exactly when the real outcome satisfied the case's stated expectation;
 * [detail] describes what the seam actually returned. Nothing here is
 * fabricated: [result] is whatever [NativeProcessExecutionSeam] genuinely
 * produced, and every EXITED/TERMINATED_BY_SIGNAL/LAUNCH_FAILED value came from
 * the real native runner.
 */
internal data class NativeProcessSelfCheckCase(
    val label: String,
    val request: NativeProcessRequest,
    val result: NativeProcessExecutionResult,
    val expectedMet: Boolean,
    val detail: String,
)

/** Aggregated result of one [NativeProcessSelfCheck] run. */
internal data class NativeProcessSelfCheckReport(
    val cases: List<NativeProcessSelfCheckCase>,
) {
    val allPassed: Boolean
        get() = cases.all { it.expectedMet }

    val passedCount: Int
        get() = cases.count { it.expectedMet }
}

/**
 * Internal, dormant end-to-end self-check for the real native process runner
 * (Part 27-P). Every case goes through the controlled seam
 * ([NativeProcessExecutionSeam]) and therefore through the policy gate and the
 * existing Part 27-O runner: it launches a REAL executable
 * ([NativeExecutionPolicy.permittedInvocations]), captures genuine stdout and
 * stderr, and observes a real exit status. Nothing is mocked and no output is
 * fabricated.
 *
 * This object is deliberately NOT wired to any Shell command, any UI, or any
 * startup path: it must never run during app startup (eager process execution
 * is a non-goal), and no user command can reach it. It exists so the app's own
 * codebase (a future test or diagnostics surface) can verify the runner
 * end-to-end on demand by calling [run] with a background [dispatcher]. The
 * verification executables are universally present Android host binaries used
 * strictly internally - they are never advertised as AliasNull features and are
 * never user-facing Shell commands.
 */
internal object NativeProcessSelfCheck {

    /**
     * Runs every self-check case against [runner] on [dispatcher] (default
     * [Dispatchers.Default]) and returns the genuine report. Blocking until all
     * children have terminated; the suspend form never touches the main thread.
     */
    suspend fun run(
        runner: AliasNullNativeRuntime,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): NativeProcessSelfCheckReport {
        val cases = mutableListOf<NativeProcessSelfCheckCase>()
        val firstRun = mutableMapOf<List<String>, NativeProcessExecutionResult>()

        for (argv in NativeExecutionPolicy.permittedInvocations) {
            val request = NativeProcessRequest(argv = argv)
            val result = NativeProcessExecutionSeam.execute(request, runner, dispatcher)
            firstRun[argv] = result
            cases += case(
                label = labelFor(argv),
                request = request,
                result = result,
                expectation = expectationFor(argv),
            )
        }

        // Repeated runs: the runner owns no cross-call state, so a second run of
        // the same argv must produce a structurally identical result.
        for (argv in REPEATED_ARGV) {
            val request = NativeProcessRequest(argv = argv)
            val second = NativeProcessExecutionSeam.execute(request, runner, dispatcher)
            val first = firstRun[argv]
            val expectedMet = first != null && second == first
            cases += NativeProcessSelfCheckCase(
                label = "F. repeated execution is stable: ${labelFor(argv).lowercase()}",
                request = request,
                result = second,
                expectedMet = expectedMet,
                detail = render(second) +
                    if (expectedMet) "" else " (differs from the first run of the same argv)",
            )
        }

        // Policy-rejection probes: these must be Rejected and must never launch a
        // child (the seam returns before reaching the native runner).
        for (probe in REJECTION_PROBES) {
            val request = NativeProcessRequest(argv = probe.argv)
            val result = NativeProcessExecutionSeam.execute(request, runner, dispatcher)
            val expectedMet = result is NativeProcessExecutionResult.Rejected &&
                result.reason.code == probe.code
            cases += NativeProcessSelfCheckCase(
                label = probe.label,
                request = request,
                result = result,
                expectedMet = expectedMet,
                detail = render(result),
            )
        }

        return NativeProcessSelfCheckReport(cases)
    }

    private fun case(
        label: String,
        request: NativeProcessRequest,
        result: NativeProcessExecutionResult,
        expectation: (NativeProcessExecutionResult) -> Boolean,
    ): NativeProcessSelfCheckCase = NativeProcessSelfCheckCase(
        label = label,
        request = request,
        result = result,
        expectedMet = expectation(result),
        detail = render(result),
    )

    private fun expectationFor(argv: List<String>): (NativeProcessExecutionResult) -> Boolean = when (argv) {
        listOf("/system/bin/echo", NativeExecutionPolicy.SELFCHECK_STDOUT_TOKEN) ->
            { result -> stdoutExpectation(result) }
        listOf("/system/bin/cat", NativeExecutionPolicy.SELFCHECK_STDERR_PATH) ->
            { result -> stderrDistinctExpectation(result) }
        listOf("/system/bin/false") ->
            { result -> nonZeroExitExpectation(result) }
        listOf(NativeExecutionPolicy.SELFCHECK_MISSING_BINARY) ->
            { result -> launchFailureExpectation(result) }
        else -> { _ -> false }
    }

    /** Successful launch: EXITED 0, the echo token on stdout, nothing on stderr. */
    private fun stdoutExpectation(result: NativeProcessExecutionResult): Boolean {
        val native = nativeOf(result) ?: return false
        return native.outcome == NativeProcessOutcome.EXITED &&
            native.exitCode == 0 &&
            native.stdout.contains(NativeExecutionPolicy.SELFCHECK_STDOUT_TOKEN) &&
            native.stderr.isEmpty()
    }

    /** stdout/stderr distinct + genuine non-zero exit stays EXITED: cat errors to
     * stderr, keeps stdout empty, and exits non-zero - never LAUNCH_FAILED. */
    private fun stderrDistinctExpectation(result: NativeProcessExecutionResult): Boolean {
        val native = nativeOf(result) ?: return false
        return native.outcome == NativeProcessOutcome.EXITED &&
            native.exitCode != null && native.exitCode != 0 &&
            native.stdout.isEmpty() &&
            native.stderr.isNotEmpty()
    }

    /** Genuine non-zero exit with no output: false exits 1, both streams empty. */
    private fun nonZeroExitExpectation(result: NativeProcessExecutionResult): Boolean {
        val native = nativeOf(result) ?: return false
        return native.outcome == NativeProcessOutcome.EXITED &&
            native.exitCode == 1 &&
            native.stdout.isEmpty() &&
            native.stderr.isEmpty()
    }

    /** Unlaunchable executable: the request reached the runner and became
     * LAUNCH_FAILED (the native layer could not resolve/exec it). */
    private fun launchFailureExpectation(result: NativeProcessExecutionResult): Boolean {
        val native = nativeOf(result) ?: return false
        return native.outcome == NativeProcessOutcome.LAUNCH_FAILED && !native.processRan
    }

    private fun nativeOf(result: NativeProcessExecutionResult): NativeProcessResult? =
        (result as? NativeProcessExecutionResult.Executed)?.result

    private fun labelFor(argv: List<String>): String = when (argv) {
        listOf("/system/bin/echo", NativeExecutionPolicy.SELFCHECK_STDOUT_TOKEN) ->
            "A. successful launch and genuine stdout capture (echo)"
        listOf("/system/bin/cat", NativeExecutionPolicy.SELFCHECK_STDERR_PATH) ->
            "B/C. stdout and stderr stay distinct; genuine non-zero exit stays EXITED (cat)"
        listOf("/system/bin/false") ->
            "C. genuine non-zero exit stays EXITED (false)"
        listOf(NativeExecutionPolicy.SELFCHECK_MISSING_BINARY) ->
            "D. unlaunchable executable becomes LAUNCH_FAILED"
        else -> "self-check: ${argv.joinToString(" ")}"
    }

    private fun render(result: NativeProcessExecutionResult): String = when (result) {
        is NativeProcessExecutionResult.Executed -> {
            val native = result.result
            "EXECUTED outcome=${native.outcome} exit=${native.exitCode} signal=${native.termSignal} " +
                "stdoutBytes=${native.stdout.length} stderrBytes=${native.stderr.length}" +
                (native.errorMessage?.let { " message=$it" } ?: "")
        }
        is NativeProcessExecutionResult.Rejected ->
            "REJECTED ${result.reason.code} ${result.reason.message}"
        is NativeProcessExecutionResult.RunnerUnavailable ->
            "RUNNER_UNAVAILABLE ${result.message}"
        is NativeProcessExecutionResult.InternalFailure ->
            "INTERNAL_FAILURE ${result.message}"
    }

    private data class RejectionProbe(
        val label: String,
        val argv: List<String>,
        val code: NativeExecutionRejectionCode,
    )

    private val REPEATED_ARGV: List<List<String>> = listOf(
        listOf("/system/bin/echo", NativeExecutionPolicy.SELFCHECK_STDOUT_TOKEN),
        listOf("/system/bin/cat", NativeExecutionPolicy.SELFCHECK_STDERR_PATH),
        listOf(NativeExecutionPolicy.SELFCHECK_MISSING_BINARY),
    )

    private val REJECTION_PROBES: List<RejectionProbe> = listOf(
        RejectionProbe(
            label = "E. policy rejects an arbitrary shell invocation (no child launched)",
            argv = listOf("/system/bin/sh", "-c", "echo must-not-run"),
            code = NativeExecutionRejectionCode.EXECUTABLE_NOT_PERMITTED,
        ),
        RejectionProbe(
            label = "E. policy rejects a permitted executable with non-allowlisted arguments",
            argv = listOf("/system/bin/echo", "not-the-allowlisted-token"),
            code = NativeExecutionRejectionCode.ARGUMENTS_NOT_PERMITTED,
        ),
        RejectionProbe(
            label = "E. policy rejects a malformed request (empty executable)",
            argv = listOf(""),
            code = NativeExecutionRejectionCode.INVALID_REQUEST,
        ),
    )
}
