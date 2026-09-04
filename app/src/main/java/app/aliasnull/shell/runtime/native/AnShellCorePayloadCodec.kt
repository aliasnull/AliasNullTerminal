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
 * [error kinds 1..4 only]
 *   u32             message byte length
 *   message bytes   (UTF-8)
 *   u8              has_span (0/1)
 *   if has_span: u32 span_start, u32 span_end   ([start, end) byte offsets)
 * ```
 *
 * Every field is length-prefixed, never delimiter-split, so arbitrary echo text
 * (embedded newlines, quotes, non-ASCII) round-trips without ambiguity. Decoding
 * is total: a frame that violates the layout (truncated, oversized lengths,
 * unknown kind byte) is reported as a structured
 * [AnShellCoreResultKind.INTERNAL_ERROR] instead of throwing, so a caller always
 * receives a value.
 */
internal object AnShellCorePayloadCodec {

    private const val KIND_SUCCESS = 0
    private const val KIND_LEXER_ERROR = 1
    private const val KIND_PARSE_ERROR = 2
    private const val KIND_SEMANTIC_ERROR = 3
    private const val KIND_INTERNAL_ERROR = 4

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
            KIND_LEXER_ERROR, KIND_PARSE_ERROR, KIND_SEMANTIC_ERROR, KIND_INTERNAL_ERROR ->
                decodeError(reader, kindByte)
            else -> internalError("The native payload kind byte $kindByte is unknown.")
        }
    }

    /** Reads the message and optional span every native error frame carries. */
    private fun decodeError(reader: Reader, kindByte: Int): AnShellCoreExecutionResult {
        val message = reader.readUtf8String() ?: return internalError("The native payload has a malformed error message.")
        val hasSpan = reader.readU8() ?: return internalError("The native payload is truncated inside its error section.")
        var spanStart: Int? = null
        var spanEnd: Int? = null
        when (hasSpan) {
            0 -> Unit // no span (internal errors never carry one)
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
        val kind = when (kindByte) {
            KIND_LEXER_ERROR -> AnShellCoreResultKind.LEXER_ERROR
            KIND_PARSE_ERROR -> AnShellCoreResultKind.PARSE_ERROR
            KIND_SEMANTIC_ERROR -> AnShellCoreResultKind.SEMANTIC_ERROR
            else -> AnShellCoreResultKind.INTERNAL_ERROR
        }
        return AnShellCoreExecutionResult.pipelineError(
            kind = kind,
            message = message,
            errorSpanStart = spanStart,
            errorSpanEnd = spanEnd,
        )
    }

    private fun internalError(message: String): AnShellCoreExecutionResult =
        AnShellCoreExecutionResult.pipelineError(
            kind = AnShellCoreResultKind.INTERNAL_ERROR,
            message = message,
        )

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
