package app.aliasnull.shell.execution

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The current temporary command backend, expressed behind [ShellCommandExecutor].
 *
 * Pure in-process simulation: `help`, `about` and `echo` emit fixed output,
 * `clear` requests a terminal clear, and anything else reports "command not
 * found". It never touches the device, a Linux runtime, a PTY, or any filesystem.
 * These commands have no streaming work, so they complete immediately, but they
 * still travel through the same Started -> ... -> Completed event stream that a
 * future runtime will use.
 */
class TemporaryShellCommandExecutor : ShellCommandExecutor {

    override fun execute(request: ShellExecutionRequest): Flow<ShellExecutionEvent> = flow {
        val trimmed = request.command.trim()
        if (trimmed.isEmpty()) {
            emit(ShellExecutionEvent.Completed())
            return@flow
        }

        val name = trimmed.substringBefore(' ')
        val normalized = name.lowercase()
        val args = trimmed.removePrefix(name).trim()

        emit(ShellExecutionEvent.Started)
        when (normalized) {
            "help" -> emit(ShellExecutionEvent.Output(helpText))
            "about" -> emit(ShellExecutionEvent.Output(aboutText))
            "clear" -> emit(ShellExecutionEvent.ClearScreen)
            "echo" -> if (args.isNotEmpty()) emit(ShellExecutionEvent.Output(args))
            else -> {
                emit(ShellExecutionEvent.Error("AN Shell: command not found: $normalized"))
                emit(ShellExecutionEvent.Output("Type 'help' to view available frontend commands."))
                emit(ShellExecutionEvent.Completed(exitCode = 1))
                return@flow
            }
        }
        emit(ShellExecutionEvent.Completed())
    }

    private companion object {
        const val helpText = "AliasNull Shell - temporary frontend commands\n" +
            "\n" +
            "Available commands:\n" +
            "help       Show this help text\n" +
            "about      About AliasNull\n" +
            "clear      Clear the terminal history\n" +
            "echo ...   Print the given text back\n" +
            "\n" +
            "These commands are simulated by the frontend executor. No Linux\n" +
            "runtime is connected."

        const val aboutText = "AliasNull\n" +
            "Mobile terminal · Native runtime · Linux environment\n" +
            "\n" +
            "The shell runtime backend is not connected yet. Only the temporary\n" +
            "frontend commands listed by 'help' are available."
    }
}
