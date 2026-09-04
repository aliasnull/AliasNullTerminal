package app.aliasnull.shell.runtime.native

/**
 * The kind of one command outcome reported by the AN Shell core bridge.
 *
 * SUCCESS, LEXER_ERROR, PARSE_ERROR, SEMANTIC_ERROR and INTERNAL_ERROR are the
 * kinds the native core itself reports (the payload `kind` bytes 0..4, see
 * [AnShellCorePayloadCodec]). BRIDGE_UNAVAILABLE never originates in Rust: the
 * Kotlin side emits it when the native core could not be loaded or the version
 * handshake failed, so a caller can always tell "the command was rejected by the
 * language core" apart from "the core itself could not be reached".
 */
enum class AnShellCoreResultKind {
    /** The command executed; output and clearRequested are meaningful. */
    SUCCESS,

    /** The lexer rejected the command text (e.g. an unterminated string). */
    LEXER_ERROR,

    /** The parser rejected the token stream. */
    PARSE_ERROR,

    /** Semantic analysis rejected the program (e.g. an unknown command name). */
    SEMANTIC_ERROR,

    /** A core-level failure that is not a language-pipeline error. */
    INTERNAL_ERROR,

    /** The native core could not be reached (load or handshake failed). */
    BRIDGE_UNAVAILABLE,
}

/**
 * Structured, honest outcome of running one command string through the native
 * AN Shell core.
 *
 * This is pure data mirroring the style of [NativeRuntimeResult]; it is never a
 * rendering and never claims a process, PTY or terminal session ran.
 *
 * [output] holds the emitted output units in order and is populated only on
 * [AnShellCoreResultKind.SUCCESS]; a unit is the exact text one emission
 * produced and may contain embedded newlines. [clearRequested] is true when any
 * executed command requested a terminal clear. On a native-reported error,
 * [errorMessage] holds the reason, and [errorSpanStart]/[errorSpanEnd] the
 * byte-offset span `[start, end)` into the original command text when the error
 * carries one (an internal error does not).
 */
data class AnShellCoreExecutionResult(
    val kind: AnShellCoreResultKind,
    val output: List<String> = emptyList(),
    val clearRequested: Boolean = false,
    val errorMessage: String? = null,
    val errorSpanStart: Int? = null,
    val errorSpanEnd: Int? = null,
) {
    /** True only when the command executed successfully. */
    val success: Boolean
        get() = kind == AnShellCoreResultKind.SUCCESS

    companion object {
        /** A command that executed; see the payload kind 0. */
        fun success(output: List<String>, clearRequested: Boolean) = AnShellCoreExecutionResult(
            kind = AnShellCoreResultKind.SUCCESS,
            output = output,
            clearRequested = clearRequested,
        )

        /** A native-reported language-pipeline or internal error. */
        fun pipelineError(
            kind: AnShellCoreResultKind,
            message: String,
            errorSpanStart: Int? = null,
            errorSpanEnd: Int? = null,
        ) = AnShellCoreExecutionResult(
            kind = kind,
            errorMessage = message,
            errorSpanStart = errorSpanStart,
            errorSpanEnd = errorSpanEnd,
        )

        /** The command was not sent because the native core could not be reached. */
        fun bridgeUnavailable(message: String) = AnShellCoreExecutionResult(
            kind = AnShellCoreResultKind.BRIDGE_UNAVAILABLE,
            errorMessage = message,
        )
    }
}
