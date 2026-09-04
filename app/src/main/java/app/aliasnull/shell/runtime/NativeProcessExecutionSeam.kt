package app.aliasnull.shell.runtime

import app.aliasnull.shell.runtime.native.AliasNullNativeRuntime
import app.aliasnull.shell.runtime.native.NativeProcessOutcome
import app.aliasnull.shell.runtime.native.NativeProcessRequest
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single controlled Kotlin-level execution seam around the existing native
 * one-shot process runner (Part 27-P).
 *
 * The seam is the *only* place that turns a structured [NativeProcessRequest]
 * into a call of [AliasNullNativeRuntime.runProcess] (and therefore of
 * [app.aliasnull.shell.runtime.native.NativeRuntimeBridge.nativeRunProcess]).
 * Every request is first passed through [NativeExecutionPolicy]; a request that
 * policy rejects returns [NativeProcessExecutionResult.Rejected] and is never
 * forwarded to native code, so no child process is launched. A request that
 * passes policy reaches the real runner and returns its genuine
 * [app.aliasnull.shell.runtime.native.NativeProcessResult] wrapped in
 * [NativeProcessExecutionResult.Executed].
 *
 * This is a controlled seam, not a Shell command backend. It is deliberately
 * NOT a [app.aliasnull.shell.execution.ShellCommandExecutor], so nothing in the
 * execution routing layer (ExecutionRouter, the AN Shell core AUTO path) can
 * route a user command or an unknown AN Shell command through it. It is not
 * wired to any command, UI, session slot or startup path; it exists for future
 * internal consumers and the internal self-check ([NativeProcessSelfCheck]),
 * and it must not be confused with [NativeExecutionSeam], which only reports
 * whether the NATIVE_RUNTIME backend could serve the Shell command contract
 * (it cannot - that backend is not executable and never receives a command).
 *
 * Threading: [AliasNullNativeRuntime.runProcess] blocks until the child
 * terminates, so execution MUST happen off the Android main thread. [execute]
 * is a suspend function that runs the blocking work on [dispatcher] (default
 * [Dispatchers.Default], the same dispatcher family the runtime manager uses);
 * it never silently uses the main dispatcher. [executeBlocking] is the raw
 * blocking form and is documented as off-main-only.
 */
object NativeProcessExecutionSeam {

    /**
     * Runs [process] through the policy gate and, when allowed, through the real
     * native runner. Suspend form: the blocking native call runs on [dispatcher]
     * (default [Dispatchers.Default]) so this is safe to call from a coroutine on
     * any context and never runs the native call on the caller's thread.
     *
     * [verifiedBaseExecutable], when non-null, marks [process] as the single
     * bundled base-userspace executable case (Part 27-S2): the policy gate is
     * then [NativeExecutionPolicy.decideBaseExecutable], which allows only that
     * one verified bare argv. It must be the installed bundled executable derived
     * from the verified base directory - never UI input; for any other request
     * it stays null and the ordinary [NativeExecutionPolicy.decide] applies.
     */
    suspend fun execute(
        process: NativeProcessRequest,
        runner: AliasNullNativeRuntime,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        verifiedBaseExecutable: File? = null,
    ): NativeProcessExecutionResult = withContext(dispatcher) {
        executeBlocking(process, runner, verifiedBaseExecutable)
    }

    /**
     * Blocking form of [execute]. MUST be called from a background thread and
     * never from the Android main/UI thread, because the native runner blocks
     * until the child terminates. Prefer the suspend [execute] form. See
     * [execute] for the meaning of [verifiedBaseExecutable].
     */
    fun executeBlocking(
        process: NativeProcessRequest,
        runner: AliasNullNativeRuntime,
        verifiedBaseExecutable: File? = null,
    ): NativeProcessExecutionResult {
        val decision = if (verifiedBaseExecutable != null) {
            NativeExecutionPolicy.decideBaseExecutable(process, verifiedBaseExecutable)
        } else {
            NativeExecutionPolicy.decide(process)
        }
        if (decision is NativeExecutionPolicyDecision.Rejected) {
            return NativeProcessExecutionResult.Rejected(decision.reason)
        }
        if (!runner.isNativeLibraryLoaded) {
            return NativeProcessExecutionResult.RunnerUnavailable(
                "libaliasnull_runtime.so is not loaded; no native process can be run.",
            )
        }
        val native = try {
            runner.runProcess(process)
        } catch (error: Throwable) {
            // runProcess is total by contract, so this only guards against a
            // genuine unexpected Kotlin-side failure.
            return NativeProcessExecutionResult.InternalFailure(
                "The native process seam failed internally: " +
                    (error.message ?: error::class.simpleName ?: "unknown"),
            )
        }
        if (native.outcome == NativeProcessOutcome.RUNNER_UNAVAILABLE) {
            return NativeProcessExecutionResult.RunnerUnavailable(
                native.errorMessage ?: "The native runner reported that no process could be run.",
            )
        }
        return NativeProcessExecutionResult.Executed(native)
    }
}
