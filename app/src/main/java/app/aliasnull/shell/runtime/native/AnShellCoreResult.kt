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
 * The stable category of a language error, mirroring the Rust bridge's
 * `CATEGORY_*` constants (1..5). Category 0 is reserved for internal errors,
 * which never carry a category byte. The numeric values are a cross-language
 * contract with the encoder and must not be reordered or renumbered.
 */
enum class AnShellCoreErrorCategory {
    /** The lexer saw an unterminated double-quoted string. */
    LEXER_UNTERMINATED_STRING,

    /** The parser expected the token stream to end but more input followed. */
    PARSE_MISSING_EOF,

    /** The parser rejected a token after the end of the program. */
    PARSE_TOKEN_AFTER_EOF,

    /** Semantic analysis found no built-in matching the command name. */
    SEMANTIC_UNKNOWN_COMMAND,

    /** Semantic analysis rejected an empty command. */
    SEMANTIC_EMPTY_COMMAND,
}

/**
 * A structured error reported for one command, separating what a shell user
 * should see from the internal detail the language core also produced.
 *
 * The native core is the single language authority: [userMessage] is the core's
 * own concise, stable wording for a shell user (never a byte offset, never the
 * full Rust error text), while [diagnostic] is the detailed internal text (which
 * may carry byte offsets) preserved for logs and future debug tooling. Kotlin
 * renders [userMessage] verbatim and never parses [diagnostic].
 *
 * [category] identifies the exact language rule that failed and is null only for
 * the infrastructure outcomes ([AnShellCoreResultKind.INTERNAL_ERROR] and
 * [AnShellCoreResultKind.BRIDGE_UNAVAILABLE]), which carry a single honest
 * message and no category. [subject] is the offending name/value exactly as the
 * user typed it when the failing rule involves one (the unknown command name),
 * sliced from the source by the language core -- never re-derived in Kotlin.
 * [spanStart]/[spanEnd] are the half-open `[start, end)` byte offsets into the
 * original command text, present only for language errors.
 */
data class AnShellCoreError(
    val userMessage: String,
    val category: AnShellCoreErrorCategory? = null,
    val diagnostic: String? = null,
    val subject: String? = null,
    val spanStart: Int? = null,
    val spanEnd: Int? = null,
)

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
 * executed command requested a terminal clear. On any non-success outcome,
 * [error] is present: a [AnShellCoreError] carrying the category/user message/
 * diagnostic/subject/span for a language error, or just a message for an
 * infrastructure failure.
 */
data class AnShellCoreExecutionResult(
    val kind: AnShellCoreResultKind,
    val output: List<String> = emptyList(),
    val clearRequested: Boolean = false,
    val error: AnShellCoreError? = null,
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

        /**
         * A language-pipeline rejection (kinds 1..3): the core's stable
         * [userMessage], its [diagnostic], the stable [category], the optional
         * [subject] and the optional [spanStart]/[spanEnd] byte span.
         */
        fun languageError(
            kind: AnShellCoreResultKind,
            category: AnShellCoreErrorCategory,
            userMessage: String,
            diagnostic: String,
            subject: String? = null,
            spanStart: Int? = null,
            spanEnd: Int? = null,
        ) = AnShellCoreExecutionResult(
            kind = kind,
            error = AnShellCoreError(
                userMessage = userMessage,
                category = category,
                diagnostic = diagnostic,
                subject = subject,
                spanStart = spanStart,
                spanEnd = spanEnd,
            ),
        )

        /** A core-level failure (kind 4) that is not a language-pipeline error. */
        fun internalError(message: String) = AnShellCoreExecutionResult(
            kind = AnShellCoreResultKind.INTERNAL_ERROR,
            error = AnShellCoreError(userMessage = message),
        )

        /** The command was not sent because the native core could not be reached. */
        fun bridgeUnavailable(message: String) = AnShellCoreExecutionResult(
            kind = AnShellCoreResultKind.BRIDGE_UNAVAILABLE,
            error = AnShellCoreError(userMessage = message),
        )
    }
}
