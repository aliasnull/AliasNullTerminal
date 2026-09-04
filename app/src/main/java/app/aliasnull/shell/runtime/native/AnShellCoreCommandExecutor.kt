package app.aliasnull.shell.runtime.native

import app.aliasnull.shell.execution.ShellCommandExecutor
import app.aliasnull.shell.execution.ShellExecutionEvent
import app.aliasnull.shell.execution.ShellExecutionRequest
import app.aliasnull.shell.execution.TemporaryShellCommandExecutor
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
 * re-implements no help/about/echo semantics. [TemporaryShellCommandExecutor]
 * remains the behavioural reference and the fallback/compat backend.
 *
 * Result -> event mapping
 * -----------------------
 * The core reports ONE aggregate outcome for the whole input (output units in
 * source order plus one clear-requested OR flag). That aggregate is rendered
 * honestly without inventing per-command interleaving:
 *
 *   - blank / whitespace-only input: [ShellExecutionEvent.Completed] only (the
 *     command was trimmed before the core saw it, mirroring the reference);
 *   - [AnShellCoreResultKind.SUCCESS]: [ShellExecutionEvent.Started], then each
 *     output unit as [ShellExecutionEvent.Output], then
 *     [ShellExecutionEvent.ClearScreen] when any command requested a clear, then
 *     [ShellExecutionEvent.Completed]. A success with no output and no clear
 *     request (for example a bare `echo`) is Started -> Completed, exactly like
 *     the reference executor.
 *
 *     The aggregate clear is emitted after the output units because the payload
 *     collapses a multi-command input into one output list plus one clear OR
 *     flag, so the executor cannot know where a clear sat in source order. A
 *     clear that followed the output in source (the ordinary `echo x` then
 *     `clear` intent, and the single `clear` command) therefore lands correctly;
 *     a clear that *preceded* later output in a multi-line submission hides that
 *     output, exactly because the ordering was already merged upstream. This is
 *     the honest rendering of the aggregate, not a redesign of the wire result.
 *   - the language-pipeline rejections [AnShellCoreResultKind.SEMANTIC_ERROR],
 *     [AnShellCoreResultKind.LEXER_ERROR] and [AnShellCoreResultKind.PARSE_ERROR]
 *     are command results: Started, then an [ShellExecutionEvent.Error] carrying
 *     the core's own message, then [ShellExecutionEvent.Completed] with exit
 *     code 1. For a semantic (unknown-command) rejection the executor also emits
 *     the same "Type 'help' ..." hint line the reference executor shows, so the
 *     unknown-command experience keeps behavioural parity while the wording stays
 *     the core's own authoritative text.
 *   - [AnShellCoreResultKind.INTERNAL_ERROR] and
 *     [AnShellCoreResultKind.BRIDGE_UNAVAILABLE] mean the language pipeline
 *     itself could not produce a command result (malformed payload, null JNI
 *     payload, or the bridge not READY). Per the [ShellExecutionEvent] contract an
 *     infrastructure-level failure ends with [ShellExecutionEvent.Failed] and
 *     never with Completed, so these kinds are emitted as Failed.
 *
 * Deliberate divergences from the reference executor (all inherited from the
 * core's documented semantics, not recreated here): quoted echo arguments are
 * echoed without their quote characters, and runs of separator whitespace inside
 * an echo collapse to a single space. For ordinary single-space, unquoted input
 * the two backends agree byte-for-byte.
 *
 * This executor never touches a process, a PTY, the filesystem or the C++
 * AliasNull native runtime. The whole command runs synchronously through the
 * deterministic core, so the emitted events appear as a single completed burst.
 */
class AnShellCoreCommandExecutor : ShellCommandExecutor {

    override fun execute(request: ShellExecutionRequest): Flow<ShellExecutionEvent> = flow {
        // The Shell trims before submission, but keep parity with the reference
        // executor's own blank-input guard: nothing is sent to the core.
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
            AnShellCoreResultKind.SEMANTIC_ERROR -> {
                emit(ShellExecutionEvent.Error(messageOf(result)))
                emit(ShellExecutionEvent.Output("Type 'help' to view available frontend commands."))
                emit(ShellExecutionEvent.Completed(exitCode = 1))
            }
            AnShellCoreResultKind.LEXER_ERROR,
            AnShellCoreResultKind.PARSE_ERROR -> {
                emit(ShellExecutionEvent.Error(messageOf(result)))
                emit(ShellExecutionEvent.Completed(exitCode = 1))
            }
            AnShellCoreResultKind.INTERNAL_ERROR,
            AnShellCoreResultKind.BRIDGE_UNAVAILABLE -> {
                emit(ShellExecutionEvent.Failed(messageOf(result)))
            }
        }
    }

    /** The core's own user-safe message, with a fallback that should never fire. */
    private fun messageOf(result: AnShellCoreExecutionResult): String =
        result.errorMessage ?: "The AN Shell core reported an error without a message."
}
