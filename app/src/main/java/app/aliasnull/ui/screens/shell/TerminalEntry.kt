package app.aliasnull.ui.screens.shell

/**
 * Kinds of lines rendered in the terminal history area.
 *
 * - [COMMAND]: a command line the user submitted (rendered with a prompt prefix).
 * - [OUTPUT]: normal command output.
 * - [ERROR]: an error/unknown-command message.
 * - [SYSTEM]: informational banner/notice text from the app itself.
 */
enum class TerminalEntryType { COMMAND, OUTPUT, ERROR, SYSTEM }

/** One immutable line of terminal history, assigned a stable [id] by the ViewModel. */
data class TerminalEntry(
    val id: Long,
    val type: TerminalEntryType,
    val content: String,
)
