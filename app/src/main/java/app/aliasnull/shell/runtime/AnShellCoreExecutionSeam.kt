package app.aliasnull.shell.runtime

import app.aliasnull.shell.execution.ExecutionBackend
import app.aliasnull.shell.execution.ExecutionBackendAvailability
import app.aliasnull.shell.execution.ExecutionBackendStatus
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeState
import app.aliasnull.shell.runtime.native.AnShellCoreBridgeStatus

/**
 * Honest availability seam for the AN Shell core execution backend.
 *
 * [ExecutionBackend.AN_SHELL_CORE] is the sole command backend in this
 * architecture: it runs a command through the packaged Rust language core. This
 * object answers
 * "can that backend execute a command right now?" from the [AnShellCoreBridgeStatus]
 * the runtime manager already observes, mirroring how [NativeExecutionSeam] describes
 * the C++ native runtime backend. It deliberately:
 *
 *   - is NOT a [app.aliasnull.shell.execution.ShellCommandExecutor], so nothing
 *     routes a command through this object,
 *   - holds no JNI reference and never loads libaliasnull_an_shell_core.so
 *     (it only reads the bridge status that another component established),
 *   - never runs or fabricates a command, and
 *   - never reports the backend executable unless the bridge is genuinely READY.
 *
 * A single [ExecutionBackendStatus.AN_SHELL_CORE_UNAVAILABLE] value covers every
 * not-ready reason; the reason's detail is preserved verbatim in the message from
 * the bridge status (not yet verified / library load failed / API version
 * mismatch), so the logs keep telling exactly why the backend is not executing.
 */
object AnShellCoreExecutionSeam {

    /**
     * Describes the AN Shell core backend's current ability to execute, in honest
     * terms. ACTIVE exactly while the bridge reports [AnShellCoreBridgeState.READY];
     * any other state is AN_SHELL_CORE_UNAVAILABLE with the bridge's own message.
     */
    fun availability(bridgeStatus: AnShellCoreBridgeStatus): ExecutionBackendAvailability {
        if (bridgeStatus.state == AnShellCoreBridgeState.READY) {
            return ExecutionBackendAvailability(
                backend = ExecutionBackend.AN_SHELL_CORE,
                status = ExecutionBackendStatus.ACTIVE,
                message = "The AN Shell core bridge is READY; commands execute through the native language core.",
            )
        }
        return ExecutionBackendAvailability(
            backend = ExecutionBackend.AN_SHELL_CORE,
            status = ExecutionBackendStatus.AN_SHELL_CORE_UNAVAILABLE,
            message = bridgeStatus.message,
        )
    }
}
