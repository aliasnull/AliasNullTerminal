package app.aliasnull.shell.runtime

import app.aliasnull.shell.runtime.native.NativeProcessResult

/**
 * Outcome of asking the controlled native process seam to run one request
 * (Part 27-P).
 *
 * The four values keep the policy layer and the native layer structurally
 * separate, so a caller never has to infer which happened by parsing text:
 *
 *   - [Executed]: the request passed policy and was sent to the existing native
 *     runner; [Executed.result] is the genuine [NativeProcessResult] the runner
 *     returned (EXITED / TERMINATED_BY_SIGNAL / LAUNCH_FAILED /
 *     INTERNAL_ERROR). A real non-zero exit stays EXITED inside the result; it
 *     is never turned into a rejection.
 *   - [Rejected]: the request was blocked by policy before native execution. No
 *     child process was launched. This is not a native process failure.
 *   - [RunnerUnavailable]: the native library/runner cannot currently be used,
 *     so nothing was attempted. A genuine native result is never fabricated.
 *   - [InternalFailure]: a genuine Kotlin-side failure that the existing result
 *     model cannot represent. Never used as the normal control path.
 */
sealed interface NativeProcessExecutionResult {

    /**
     * The request reached the native runner and [result] is the genuine outcome
     * it reported. [processRan] is true only when the underlying result describes
     * a child that genuinely started and terminated.
     */
    data class Executed(val result: NativeProcessResult) : NativeProcessExecutionResult {
        val processRan: Boolean
            get() = result.processRan
    }

    /** The request was refused by policy before any native execution. */
    data class Rejected(val reason: NativeExecutionRejection) : NativeProcessExecutionResult

    /** The native runner could not be reached/used; nothing was attempted. */
    data class RunnerUnavailable(val message: String) : NativeProcessExecutionResult

    /** A genuine Kotlin-side seam failure not representable by a native result. */
    data class InternalFailure(val message: String) : NativeProcessExecutionResult
}
