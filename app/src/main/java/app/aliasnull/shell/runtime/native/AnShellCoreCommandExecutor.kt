package app.aliasnull.shell.runtime.native

import android.util.Log
import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.ShellExecutionEvent
import app.aliasnull.shell.execution.ShellExecutionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The AN Shell core command backend: a genuinely executable [ShellCommandExecutor]
 * that sends one command string through the packaged Rust language core and maps
 * the single aggregate [AnShellCoreExecutionResult] onto the existing
 * [ShellExecutionEvent] stream.
 *
 * Command authority
 * -----------------
 * This executor calls ONLY the [AnShellCoreBridge] facade ([AnShellCoreBridge.execute]),
 * never [AnShellCoreNativeBridge] or a raw JNI symbol, so the single loadLibrary /
 * external-declaration owner of libaliasnull_an_shell_core.so is preserved and the
 * Kotlin <-> Rust boundary has exactly one voice. The Rust core is the single
 * language authority: Kotlin re-derives no command name, re-splits no words and
 * re-implements no help/about/echo semantics. This is the AN Shell core backend
 * and the only command backend; there is no frontend fallback behind it.
 *
 * Result -> event mapping
 * -----------------------
 * The core reports ONE aggregate outcome for the whole input (output units in
 * source order plus one clear-requested OR flag). That aggregate is rendered
 * honestly without inventing per-command interleaving:
 *
 *   - blank / whitespace-only input: [ShellExecutionEvent.Completed] only (the
 *     command was trimmed before the core saw it);
 *   - [AnShellCoreResultKind.SUCCESS]: [ShellExecutionEvent.Started], then each
 *     output unit as [ShellExecutionEvent.Output], then
 *     [ShellExecutionEvent.ClearScreen] when any command requested a clear, then
 *     [ShellExecutionEvent.Completed]. A success with no output and no clear
 *     request (for example a bare `echo`) is Started -> Completed.
 *
 *     The aggregate clear is emitted after the output units because the payload
 *     collapses a multi-command input into one output list plus one clear OR
 *     flag, so the executor cannot know where a clear sat in source order. A
 *     clear that followed the output in source (the ordinary `echo x` then
 *     `clear` intent, and the single `clear` command) therefore lands correctly;
 *     a clear that *preceded* later output in a multi-line submission hides that
 *     output, exactly because the ordering was already merged upstream. This is
 *     the honest rendering of the aggregate, not a redesign of the wire result.
 *   - the language-pipeline rejections [AnShellCoreResultKind.LEXER_ERROR],
 *     [AnShellCoreResultKind.PARSE_ERROR] and
 *     [AnShellCoreResultKind.SEMANTIC_ERROR] are command results: Started, then
 *     an [ShellExecutionEvent.Error] whose text is `"$ "` (a terminal-style
 *     dollar-space prefix) plus the core's own concise
 *     [AnShellCoreError.userMessage], then
 *     [ShellExecutionEvent.Completed] with exit code 1. The prefix is the
 *     only text Kotlin adds: the wording itself is the core's authoritative,
 *     user-safe message (no byte offsets, never the internal diagnostic).
 *
 *     Only for the unknown-command category
 *     ([AnShellCoreErrorCategory.SEMANTIC_UNKNOWN_COMMAND]) does the executor
 *     also emit a "Type 'help' ..." hint line, so an unknown command points the
 *     user at the built-in list. Every other category is
 *     rendered without a hint, because the hint is a property of that one
 *     semantic rule -- not of message text -- and Kotlin does not parse text to
 *     decide it.
 *
 *     The rich [AnShellCoreError.diagnostic] (which may carry byte offsets) is
 *     never shown to the user; it is logged at debug level so the internal detail
 *     survives in logcat without leaking into the shell.
 *   - [AnShellCoreResultKind.INTERNAL_ERROR] and
 *     [AnShellCoreResultKind.BRIDGE_UNAVAILABLE] mean the language pipeline
 *     itself could not produce a command result (malformed payload, null JNI
 *     payload, or the bridge not READY). Per the [ShellExecutionEvent] contract an
 *     infrastructure-level failure ends with [ShellExecutionEvent.Failed] and
 *     never with Completed, so these kinds are emitted as Failed.
 *
 * Documented core semantics honoured here (not recreated in Kotlin): quoted
 * echo arguments are echoed without their quote characters, and runs of
 * separator whitespace inside an echo collapse to a single space.
 *
 * This executor never touches a process, a PTY, the filesystem or the C++
 * AliasNull native runtime. The whole command runs synchronously through the
 * deterministic core, so the emitted events appear as a single completed burst.
 */
class AnShellCoreCommandExecutor : ShellCommandExecutor {

    override fun execute(request: ShellExecutionRequest): Flow<ShellExecutionEvent> = flow {
        // The Shell trims before submission; keep the same blank-input guard
        // here so nothing is ever sent to the core for an empty line.
        val command = request.command.trim()
        if (command.isEmpty()) {
            emit(ShellExecutionEvent.Completed())
            return@flow
        }

        emit(ShellExecutionEvent.Started)
        // The core call crosses the JNI boundary and runs the Rust pipeline; the
        // Shell collects on the main dispatcher, so it is offloaded to the same
        // background dispatcher the bridge probe already uses.
        val result = withContext(Dispatchers.Default) { AnShellCoreBridge.execute(command) }

        when (result.kind) {
            AnShellCoreResultKind.SUCCESS -> {
                for (unit in result.output) {
                    emit(ShellExecutionEvent.Output(unit))
                }
                if (result.clearRequested) {
                    emit(ShellExecutionEvent.ClearScreen)
                }
                emit(ShellExecutionEvent.Completed())
            }
            AnShellCoreResultKind.LEXER_ERROR,
            AnShellCoreResultKind.PARSE_ERROR,
            AnShellCoreResultKind.SEMANTIC_ERROR -> {
                val error = result.error
                emit(ShellExecutionEvent.Error("\$ ${messageOf(result)}"))

                if (error?.category == AnShellCoreErrorCategory.SEMANTIC_UNKNOWN_COMMAND) {
                    emit(ShellExecutionEvent.Output("Type 'help' to view available commands."))
                }
                emit(ShellExecutionEvent.Completed(exitCode = 1))

                if (error?.diagnostic != null) {
                    Log.d(TAG, "AN Shell core language error for \"$command\": " +
                        "category=${error.category}, subject=${error.subject}, " +
                        "span=[${error.spanStart},${error.spanEnd}), diagnostic=${error.diagnostic}")
                }
            }
            AnShellCoreResultKind.INTERNAL_ERROR,
            AnShellCoreResultKind.BRIDGE_UNAVAILABLE -> {
                emit(ShellExecutionEvent.Failed(messageOf(result)))
            }
        }
    }

    /** The core's own user-safe message, with a fallback that should never fire. */
    private fun messageOf(result: AnShellCoreExecutionResult): String =
        result.error?.userMessage ?: "The AN Shell core reported an error without a message."

    private companion object {
        const val TAG = "AnShellCoreCommandExecutor"
    }
}
