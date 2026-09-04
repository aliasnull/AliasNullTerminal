package app.aliasnull.shell.execution

import kotlinx.coroutines.flow.Flow

/**
 * Identifies one command execution: which shell session submitted it and the raw
 * command line. Extra per-execution context (working directory, environment)
 * can be added here later without changing the executor contract.
 */
data class ShellExecutionRequest(
    val sessionId: Long,
    val command: String,
)

/**
 * Accepts a submitted command for a shell session and turns it into a stream of
 * [ShellExecutionEvent]s.
 *
 * This is the seam the Shell UI and its ViewModel execute commands through; the
 * concrete executor behind it is chosen by the runtime and the execution routing
 * layer (the AN Shell core once its bridge is READY). Implementations must not
 * depend on Compose UI and must never block the calling thread; work that
 * produces output over time does so by emitting events gradually.
 */
interface ShellCommandExecutor {
    fun execute(request: ShellExecutionRequest): Flow<ShellExecutionEvent>
}
