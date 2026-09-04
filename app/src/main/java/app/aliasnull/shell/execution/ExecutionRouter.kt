package app.aliasnull.shell.execution

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Outcome of resolving one execution request to a backend.
 *
 * The three routing concepts are kept distinct:
 *
 *   - [requestedBackend] - what the caller asked for, or null for the AUTO /
 *     default policy (no explicit request).
 *   - [backend]          - which backend was actually selected.
 *   - [status]/[executor]- whether that backend can genuinely execute and, if
 *     so, the real [ShellCommandExecutor] to run on.
 *
 * [executor] is non-null exactly when the backend really executed commands
 * ([canExecute]). A request for a non-executable backend never receives an
 * executor, so a command can never be handed to a seam or a fake backend.
 */
data class ExecutionRoute(
    val requestedBackend: ExecutionBackend? = null,
    val backend: ExecutionBackend?,
    val status: ExecutionBackendStatus,
    val executor: ShellCommandExecutor?,
    val message: String,
) {
    /** True only when this route resolves to a backend that will really execute. */
    val canExecute: Boolean
        get() = status == ExecutionBackendStatus.ACTIVE && executor != null && backend != null
}

/**
 * The single, centralized execution-routing layer: the decision point that picks
 * which backend receives a shell command.
 *
 * Responsibilities are deliberately narrow:
 *
 *   - resolve an execution request to a genuinely executable backend, and
 *   - delegate execution to that backend's real [ShellCommandExecutor].
 *
 * It does NOT own Compose/UI state, does NOT call JNI, does NOT create or close
 * native sessions, does NOT bootstrap the native runtime, does NOT implement a
 * shell or terminal, and does NOT parse ANSI. It only holds the executable
 * backends registered by the runtime and a caller-supplied availability report.
 *
 * Only backends that hold a real [ShellCommandExecutor] can ever be returned as
 * [ExecutionRoute.executor]; a backend whose availability is not
 * [ExecutionBackendStatus.ACTIVE] (or that has no registered executor) is never
 * handed a command.
 */
class ExecutionRouter(
    /** Backends that currently hold a genuinely executable [ShellCommandExecutor]. */
    private val executableBackends: Map<ExecutionBackend, ShellCommandExecutor>,
    /** Supplies the current availability of any known backend (no JNI here). */
    private val availabilityOf: (ExecutionBackend) -> ExecutionBackendAvailability,
) : ShellCommandExecutor {

    init {
        require(executableBackends.isNotEmpty()) {
            "An ExecutionRouter needs at least one genuinely executable backend."
        }
    }

    /**
     * Executes [request] on the backend selected by the AUTO policy. This is the
     * path the Shell uses today; it never fabricates output and never pretends a
     * non-executable backend ran.
     */
    override fun execute(request: ShellExecutionRequest): Flow<ShellExecutionEvent> {
        val route = resolveAuto()
        val active = route.executor
            ?: return flowOf(
                ShellExecutionEvent.Failed("No executable execution backend is available (${route.status})."),
            )
        return active.execute(request)
    }

    /**
     * AUTO / default policy: select the first genuinely executable backend from
     * a fixed preference order. The AN Shell core is the only shell command
     * backend, so AUTO resolves to it exactly when its
     * libaliasnull_an_shell_core.so bridge is READY. The C++ native runtime is
     * never executable at this milestone, so it is not in the AUTO order. When
     * the AN Shell core is not ready there is no fallback: AUTO returns a
     * non-executable route and nothing is run.
     */
    fun resolveAuto(): ExecutionRoute {
        for (backend in AUTO_PREFERENCE_ORDER) {
            val route = resolve(backend)
            if (route.canExecute) return route
        }
        return ExecutionRoute(
            backend = null,
            status = ExecutionBackendStatus.BACKEND_SELECTION_FAILED,
            executor = null,
            message = "No genuinely executable backend could be selected; the AN Shell core is not ready.",
        )
    }

    /**
     * Explicit selection of [preferred]. Honest by construction: the selected
     * backend is never silently swapped for another. If the preferred backend is
     * executable the route carries its real executor; otherwise the route keeps
     * the exact Part 26-I reason (library unavailable / not ready / not
     * implemented) and carries no executor, so nothing is executed.
     */
    fun resolve(preferred: ExecutionBackend): ExecutionRoute {
        val availability = availabilityOf(preferred)
        val executor = executableBackends[preferred]
        val executableNow = availability.status == ExecutionBackendStatus.ACTIVE && executor != null
        return if (executableNow) {
            ExecutionRoute(
                requestedBackend = preferred,
                backend = preferred,
                status = ExecutionBackendStatus.ACTIVE,
                executor = executor,
                message = availability.message,
            )
        } else {
            // No executor: the reason is preserved verbatim. If availability ever
            // claimed ACTIVE without an executor (should not happen), report that
            // execution is not implemented rather than fabricate an ACTIVE route.
            val honestStatus =
                if (availability.status == ExecutionBackendStatus.ACTIVE) {
                    ExecutionBackendStatus.NATIVE_EXECUTION_NOT_IMPLEMENTED
                } else {
                    availability.status
                }
            ExecutionRoute(
                requestedBackend = preferred,
                backend = preferred,
                status = honestStatus,
                executor = null,
                message = availability.message,
            )
        }
    }

    private companion object {
        /** AUTO preference order: the AN Shell core, the only shell command backend.
         * It is checked against its live availability, so AUTO resolves to it only
         * once its bridge is genuinely READY; otherwise AUTO selects nothing. */
        val AUTO_PREFERENCE_ORDER = listOf(
            ExecutionBackend.AN_SHELL_CORE,
        )
    }
}
