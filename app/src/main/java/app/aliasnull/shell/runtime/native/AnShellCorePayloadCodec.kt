package app.aliasnull.shell.runtime.native

/**
 * Decodes the cross-language result payload returned by
 * [AnShellCoreNativeBridge.executeCommandBytes] into a structured
 * [AnShellCoreExecutionResult].
 *
 * The payload layout is a strict little-endian frame produced by the Rust
 * bridge (`rust/aliasnull_an_shell_core/src/bridge.rs`) and is the single
 * documented byte contract between the two sides:
 *
 * ```
 * byte 0            kind (0 success, 1 lexer error, 2 parse error,
 *                          3 semantic error, 4 internal error)
 * byte 1            clear_requested (0/1; meaningful only on success)
 * u32               output unit count N (0 for errors)
 * N * (u32 len + UTF-8 bytes)     the output units, in order
 *
 * [language error kinds 1..3 only]
 *   u8              error category (see the CATEGORY_* mapping in this file)
 *   u8              has_subject (0/1)
 *   if has_subject: u32 subject byte length + UTF-8 bytes
 *   u32             user_message byte length + UTF-8 bytes
 *   u32             diagnostic byte length + UTF-8 bytes
 *   u8              has_span (0/1)
 *   if has_span: u32 span_start, u32 span_end   ([start, end) byte offsets)
 *
 * [internal error kind 4 only]
 *   u32             message byte length + UTF-8 bytes
 *   u8              has_span (0/1)     (always 0)
 * ```
 *
 * Every field is length-prefixed, never delimiter-split, so arbitrary echo text
 * (embedded newlines, quotes, non-ASCII) round-trips without ambiguity. Decoding
 * is total: a frame that violates the layout (truncated, oversized lengths,
 * unknown kind byte, a category that contradicts its kind byte) is reported as a
 * structured [AnShellCoreResultKind.INTERNAL_ERROR] instead of throwing, so a
 * caller always receives a value. This decoder never parses Rust error text: it
 * only reads the length-prefixed fields and enforces the structural mapping
 * between the kind byte and the category byte.
 */
internal object AnShellCorePayloadCodec {

    private const val KIND_SUCCESS = 0
    private const val KIND_LEXER_ERROR = 1
    private const val KIND_PARSE_ERROR = 2
    private const val KIND_SEMANTIC_ERROR = 3
    private const val KIND_INTERNAL_ERROR = 4

    // Category codes mirroring the Rust bridge constants; only these values may
    // ride on language error kinds 1..3.
    private const val CATEGORY_LEXER_UNTERMINATED_STRING = 1
    private const val CATEGORY_PARSE_MISSING_EOF = 2
    private const val CATEGORY_PARSE_TOKEN_AFTER_EOF = 3
    private const val CATEGORY_SEMANTIC_UNKNOWN_COMMAND = 4
    private const val CATEGORY_SEMANTIC_EMPTY_COMMAND = 5

    /** Decodes a native payload frame; never throws. */
    fun decode(payload: ByteArray): AnShellCoreExecutionResult {
        if (payload.isEmpty()) {
            return internalError("The native payload was empty.")
        }
        val reader = Reader(payload)
        val kindByte = reader.readU8() ?: return internalError("The native payload is shorter than its header.")
        val clearRequested = reader.readU8() ?: return internalError("The native payload is shorter than its header.")
        val output = reader.readUtf8List() ?: return internalError("The native payload has a malformed output section.")

        return when (kindByte) {
            KIND_SUCCESS -> AnShellCoreExecutionResult.success(output, clearRequested == 1)
            KIND_LEXER_ERROR, KIND_PARSE_ERROR, KIND_SEMANTIC_ERROR ->
                decodeLanguageError(reader, kindByte)
            KIND_INTERNAL_ERROR ->
                decodeInternalError(reader)
            else -> internalError("The native payload kind byte $kindByte is unknown.")
        }
    }

    /** Reads a language-error frame (kinds 1..3). */
    private fun decodeLanguageError(reader: Reader, kindByte: Int): AnShellCoreExecutionResult {
        val categoryByte = reader.readU8() ?: return internalError("The native payload has a malformed error category.")
        val category = categoryOf(categoryByte)
            ?: return internalError("The native payload error category $categoryByte is unknown.")
        val expectedKind = kindOfCategory(category)
        val kind = when (kindByte) {
            KIND_LEXER_ERROR -> AnShellCoreResultKind.LEXER_ERROR
            KIND_PARSE_ERROR -> AnShellCoreResultKind.PARSE_ERROR
            else -> AnShellCoreResultKind.SEMANTIC_ERROR
        }
        if (kind != expectedKind) {
            return internalError(
                "The native payload pairs category $categoryByte with an inconsistent kind byte $kindByte.",
            )
        }

        val hasSubject = reader.readU8() ?: return internalError("The native payload is truncated inside its error subject.")
        val subject = when (hasSubject) {
            0 -> null
            1 -> reader.readUtf8String() ?: return internalError("The native payload has a malformed error subject.")
            else -> return internalError("The native payload has an invalid error-subject flag.")
        }
        val userMessage = reader.readUtf8String() ?: return internalError("The native payload has a malformed error user message.")
        val diagnostic = reader.readUtf8String() ?: return internalError("The native payload has a malformed error diagnostic.")

        val hasSpan = reader.readU8() ?: return internalError("The native payload is truncated inside its error section.")
        var spanStart: Int? = null
        var spanEnd: Int? = null
        when (hasSpan) {
            0 -> Unit
            1 -> {
                val start = reader.readU32() ?: return internalError("The native payload is truncated inside its error span.")
                val end = reader.readU32() ?: return internalError("The native payload is truncated inside its error span.")
                if (start > Int.MAX_VALUE.toLong() || end > Int.MAX_VALUE.toLong()) {
                    return internalError("The native payload error span is out of range.")
                }
                spanStart = start.toInt()
                spanEnd = end.toInt()
            }
            else -> return internalError("The native payload has an invalid error-span flag.")
        }

        return AnShellCoreExecutionResult.languageError(
            kind = kind,
            category = category,
            userMessage = userMessage,
            diagnostic = diagnostic,
            subject = subject,
            spanStart = spanStart,
            spanEnd = spanEnd,
        )
    }

    /** Reads an internal-error frame (kind 4): one message, never a span. */
    private fun decodeInternalError(reader: Reader): AnShellCoreExecutionResult {
        val message = reader.readUtf8String() ?: return internalError("The native payload has a malformed internal error message.")
        val hasSpan = reader.readU8() ?: return internalError("The native payload is truncated inside its error section.")
        return if (hasSpan == 0) {
            AnShellCoreExecutionResult.internalError(message)
        } else {
            internalError("The native payload claims a span on an internal error.")
        }
    }

    /** Maps a category byte to its enum, or null when the value is not a known category. */
    private fun categoryOf(byte: Int): AnShellCoreErrorCategory? = when (byte) {
        CATEGORY_LEXER_UNTERMINATED_STRING -> AnShellCoreErrorCategory.LEXER_UNTERMINATED_STRING
        CATEGORY_PARSE_MISSING_EOF -> AnShellCoreErrorCategory.PARSE_MISSING_EOF
        CATEGORY_PARSE_TOKEN_AFTER_EOF -> AnShellCoreErrorCategory.PARSE_TOKEN_AFTER_EOF
        CATEGORY_SEMANTIC_UNKNOWN_COMMAND -> AnShellCoreErrorCategory.SEMANTIC_UNKNOWN_COMMAND
        CATEGORY_SEMANTIC_EMPTY_COMMAND -> AnShellCoreErrorCategory.SEMANTIC_EMPTY_COMMAND
        else -> null
    }

    /** The kind a language category must ride on; used for structural validation. */
    private fun kindOfCategory(category: AnShellCoreErrorCategory): AnShellCoreResultKind = when (category) {
        AnShellCoreErrorCategory.LEXER_UNTERMINATED_STRING -> AnShellCoreResultKind.LEXER_ERROR
        AnShellCoreErrorCategory.PARSE_MISSING_EOF,
        AnShellCoreErrorCategory.PARSE_TOKEN_AFTER_EOF -> AnShellCoreResultKind.PARSE_ERROR
        AnShellCoreErrorCategory.SEMANTIC_UNKNOWN_COMMAND,
        AnShellCoreErrorCategory.SEMANTIC_EMPTY_COMMAND -> AnShellCoreResultKind.SEMANTIC_ERROR
    }

    private fun internalError(message: String): AnShellCoreExecutionResult =
        AnShellCoreExecutionResult.internalError(message)

    /** Bounds-checked reader over the frame bytes. Never indexes out of range. */
    private class Reader(private val bytes: ByteArray) {
        private var position = 0
        private val remaining: Int
            get() = bytes.size - position

        /** Reads one unsigned byte, or null at end of input. */
        fun readU8(): Int? {
            if (remaining < 1) return null
            return bytes[position++].toInt() and 0xFF
        }

        /** Reads one little-endian u32, or null when fewer than 4 bytes remain. */
        fun readU32(): Long? {
            if (remaining < 4) return null
            var value = 0L
            for (i in 0 until 4) {
                value = value or ((bytes[position + i].toLong() and 0xFF) shl (8 * i))
            }
            position += 4
            return value
        }

        /** Reads one length-prefixed UTF-8 string, or null when malformed. */
        fun readUtf8String(): String? {
            val length = readU32() ?: return null
            if (length > remaining.toLong()) return null
            val text = String(bytes, position, length.toInt(), Charsets.UTF_8)
            position += length.toInt()
            return text
        }

        /** Reads the u32 count plus that many length-prefixed strings. */
        fun readUtf8List(): List<String>? {
            val count = readU32() ?: return null
            // Each element needs at least its 4-byte length prefix; bail out of an
            // absurd count that the remaining bytes could never satisfy.
            if (count > remaining.toLong() / 4) return null
            val items = ArrayList<String>(count.toInt())
            for (i in 0 until count.toInt()) {
                val item = readUtf8String() ?: return null
                items.add(item)
            }
            return items
        }
    }
}
