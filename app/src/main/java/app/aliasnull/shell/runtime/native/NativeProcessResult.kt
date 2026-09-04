package app.aliasnull.shell.runtime.native

/**
 * The lifecycle outcome of one native process-run request.
 *
 * The first four values mirror the native runner's outcome bytes 0..3 (see the
 * payload contract in process_execution_jni.cpp) and must not be reordered or
 * renumbered. [RUNNER_UNAVAILABLE] never originates in native code: the Kotlin
 * side emits it when libaliasnull_runtime.so could not be loaded, so a caller
 * can always tell "the process genuinely ran" from "the runner itself could not
 * be reached". A process that was launched and then exited with a non-zero code
 * is still [EXITED] with that real [NativeProcessResult.exitCode]; it is never
 * flattened into a launch failure, and a launch failure is never reported as an
 * exit code.
 */
enum class NativeProcessOutcome {
    /** The child terminated normally; [NativeProcessResult.exitCode] is present. */
    EXITED,

    /** The child was terminated by a signal; [NativeProcessResult.termSignal] is present. */
    TERMINATED_BY_SIGNAL,

    /** The requested executable could not be resolved or started; no exec ran. */
    LAUNCH_FAILED,

    /** The runner itself failed (pipe/fork/wait/read); no child result is claimed. */
    INTERNAL_ERROR,

    /** libaliasnull_runtime.so could not be loaded, so nothing could be attempted. */
    RUNNER_UNAVAILABLE,
}

/**
 * Structured, honest outcome of running one real child process (Part 27-O).
 *
 * This is pure data mirroring the style of [NativeRuntimeResult]. Every byte of
 * [stdout] and [stderr] and every [exitCode]/[termSignal] came from the actual
 * child; nothing here is fabricated. [stdout] and [stderr] are kept structurally
 * separate so a caller decides how to present them and never has to re-derive
 * one stream from the other.
 *
 * [processRan] is true only for [NativeProcessOutcome.EXITED] and
 * [NativeProcessOutcome.TERMINATED_BY_SIGNAL] - the process genuinely started
 * and then terminated. [exitCode] is present only for EXITED, [termSignal] only
 * for TERMINATED_BY_SIGNAL, and [errorMessage] only for a launch/internal
 * failure (never to dress up a real exit status). Kotlin never infers process
 * success by parsing [stdout]/[stderr] text.
 */
data class NativeProcessResult(
    val outcome: NativeProcessOutcome,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val termSignal: Int? = null,
    val errorMessage: String? = null,
) {
    /** True only when a real child process started and then terminated. */
    val processRan: Boolean
        get() = outcome == NativeProcessOutcome.EXITED ||
            outcome == NativeProcessOutcome.TERMINATED_BY_SIGNAL

    companion object {
        /** The child exited normally with its real [exitCode]. */
        fun exited(exitCode: Int, stdout: String, stderr: String) = NativeProcessResult(
            outcome = NativeProcessOutcome.EXITED,
            stdout = stdout,
            stderr = stderr,
            exitCode = exitCode,
        )

        /** The child was terminated by a signal; [termSignal] is the signal value. */
        fun terminatedBySignal(termSignal: Int, stdout: String, stderr: String) =
            NativeProcessResult(
                outcome = NativeProcessOutcome.TERMINATED_BY_SIGNAL,
                stdout = stdout,
                stderr = stderr,
                termSignal = termSignal,
            )

        /** The executable could not be resolved/started; no process result exists. */
        fun launchFailed(message: String) = NativeProcessResult(
            outcome = NativeProcessOutcome.LAUNCH_FAILED,
            errorMessage = message,
        )

        /** The runner itself failed before producing a process result. */
        fun internalError(message: String) = NativeProcessResult(
            outcome = NativeProcessOutcome.INTERNAL_ERROR,
            errorMessage = message,
        )

        /** The native runner could not be reached (library load / boundary failure). */
        fun runnerUnavailable(message: String) = NativeProcessResult(
            outcome = NativeProcessOutcome.RUNNER_UNAVAILABLE,
            errorMessage = message,
        )
    }
}
