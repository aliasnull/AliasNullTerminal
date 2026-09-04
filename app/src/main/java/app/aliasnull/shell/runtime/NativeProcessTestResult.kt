package app.aliasnull.shell.runtime

/**
 * Structured outcome of one controlled native-process self-check run (Part 27-Q).
 *
 * The two branches keep "could not even try" structurally separate from "the
 * controlled seam produced a genuine outcome":
 *
 *   - [NotReady]: the native runtime is not loaded/bootstrapped, so no child was
 *     attempted. Not a process failure and never a fabricated result.
 *   - [Outcome]: the request was sent through [NativeProcessExecutionSeam] and
 *     [Outcome.execution] is the full, genuine [NativeProcessExecutionResult] the
 *     seam returned (Executed / Rejected / RunnerUnavailable / InternalFailure),
 *     with the underlying native process outcome (EXITED / LAUNCH_FAILED / ...)
 *     still intact inside it. Nothing is flattened into a string. [expectedMet]
 *     is true only when the case's stated expectation was genuinely met; for the
 *     authorized cases a REJECTED/RUNNER_UNAVAILABLE/INTERNAL_FAILURE outcome is
 *     unexpected, so [expectedMet] is false and the UI shows the real category.
 */
sealed interface NativeProcessTestResult {
    /** The case this result describes. */
    val kind: NativeProcessTestKind

    /**
     * The native runtime could not be used, so the case was not attempted. This
     * is a readiness fact, not a process outcome; [message] explains it in plain
     * terms and no child process was launched.
     */
    data class NotReady(
        override val kind: NativeProcessTestKind,
        val message: String,
    ) : NativeProcessTestResult

    /**
     * The controlled seam ran the case's authorized request and [execution] is
     * the genuine structured outcome. [expectedMet] tells whether that outcome
     * matched the case's stated expectation; the caller shows the outcome
     * categories and streams from [execution] truthfully.
     */
    data class Outcome(
        override val kind: NativeProcessTestKind,
        val execution: NativeProcessExecutionResult,
        val expectedMet: Boolean,
    ) : NativeProcessTestResult
}
