package app.aliasnull.shell.runtime.native

/**
 * Decodes the cross-language process-runner payload returned by
 * [NativeRuntimeBridge] (via its native entry point) into a structured
 * [NativeProcessResult].
 *
 * The payload layout is a strict little-endian frame produced by the native
 * runner (`app/src/main/cpp/process_execution_jni.cpp`) and is the single
 * documented byte contract between the two sides:
 *
 * ```
 * byte 0       outcome (0 EXITED, 1 TERMINATED_BY_SIGNAL, 2 LAUNCH_FAILED,
 *                      3 INTERNAL_ERROR)
 * u32          stdout byte length; stdout bytes
 * u32          stderr byte length; stderr bytes
 * u8           has_exit (1 only for outcome 0)
 * if has_exit: i32 exit_code
 * u8           has_signal (1 only for outcome 1)
 * if has_signal: i32 term_signal
 * u32          error-message byte length; error-message bytes
 * ```
 *
 * Every field is length-prefixed, never delimiter-split, so arbitrary captured
 * bytes (embedded newlines, spaces, non-ASCII, even NULs) round-trip without
 * ambiguity. Decoding is total: a frame that violates the layout (truncated,
 * oversized lengths, unknown outcome byte, a flag that contradicts its outcome)
 * is reported as a structured [NativeProcessOutcome.INTERNAL_ERROR] instead of
 * throwing. Captured bytes are decoded leniently as UTF-8 (invalid sequences
 * become U+FFFD) because child output is text in practice; the byte lengths
 * still carry the true boundaries.
 */
internal object NativeProcessPayloadCodec {

    private const val OUTCOME_EXITED = 0
    private const val OUTCOME_TERMINATED_BY_SIGNAL = 1
    private const val OUTCOME_LAUNCH_FAILED = 2
    private const val OUTCOME_INTERNAL_ERROR = 3

    /** Decodes a native process-runner payload; never throws. */
    fun decode(payload: ByteArray): NativeProcessResult {
        if (payload.isEmpty()) {
            return NativeProcessResult.internalError("The native process payload was empty.")
        }
        val reader = Reader(payload)

        val outcomeByte = reader.readU8() ?: return internalError("The native process payload is shorter than its header.")
        val outcome = outcomeOf(outcomeByte)
            ?: return internalError("The native process payload outcome byte $outcomeByte is unknown.")
        val stdout = reader.readUtf8String() ?: return internalError("The native process payload has a malformed stdout field.")
        val stderr = reader.readUtf8String() ?: return internalError("The native process payload has a malformed stderr field.")

        val hasExit = reader.readU8() ?: return internalError("The native process payload is truncated inside its exit field.")
        if (hasExit !in 0..1) return internalError("The native process payload has an invalid exit-flag value.")
        var exitCode: Int? = null
        if (hasExit == 1) {
            if (outcome != NativeProcessOutcome.EXITED) {
                return internalError("The native process payload pairs an exit code with a non-EXITED outcome.")
            }
            exitCode = reader.readI32() ?: return internalError("The native process payload has a malformed exit code.")
        } else if (outcome == NativeProcessOutcome.EXITED) {
            return internalError("The native process payload omitted the exit code of an EXITED outcome.")
        }

        val hasSignal = reader.readU8() ?: return internalError("The native process payload is truncated inside its signal field.")
        if (hasSignal !in 0..1) return internalError("The native process payload has an invalid signal-flag value.")
        var termSignal: Int? = null
        if (hasSignal == 1) {
            if (outcome != NativeProcessOutcome.TERMINATED_BY_SIGNAL) {
                return internalError("The native process payload pairs a signal with a non-TERMINATED_BY_SIGNAL outcome.")
            }
            termSignal = reader.readI32() ?: return internalError("The native process payload has a malformed signal value.")
        } else if (outcome == NativeProcessOutcome.TERMINATED_BY_SIGNAL) {
            return internalError("The native process payload omitted the signal of a TERMINATED_BY_SIGNAL outcome.")
        }

        val message = reader.readUtf8String() ?: return internalError("The native process payload has a malformed error message.")

        return when (outcome) {
            NativeProcessOutcome.EXITED -> NativeProcessResult.exited(
                exitCode = exitCode ?: 0,
                stdout = stdout,
                stderr = stderr,
            )
            NativeProcessOutcome.TERMINATED_BY_SIGNAL -> NativeProcessResult.terminatedBySignal(
                termSignal = termSignal ?: 0,
                stdout = stdout,
                stderr = stderr,
            )
            NativeProcessOutcome.LAUNCH_FAILED -> NativeProcessResult.launchFailed(
                message = message.ifEmpty { "The process could not be launched." },
            )
            NativeProcessOutcome.INTERNAL_ERROR -> NativeProcessResult.internalError(
                message = message.ifEmpty { "The process runner reported an internal error." },
            )
            NativeProcessOutcome.RUNNER_UNAVAILABLE ->
                // Unreachable: native code never emits this byte.
                NativeProcessResult.internalError("The native process payload claimed a runner-unavailable outcome.")
        }
    }

    /** Maps an outcome byte to its enum, or null when the value is not a known outcome. */
    private fun outcomeOf(byte: Int): NativeProcessOutcome? = when (byte) {
        OUTCOME_EXITED -> NativeProcessOutcome.EXITED
        OUTCOME_TERMINATED_BY_SIGNAL -> NativeProcessOutcome.TERMINATED_BY_SIGNAL
        OUTCOME_LAUNCH_FAILED -> NativeProcessOutcome.LAUNCH_FAILED
        OUTCOME_INTERNAL_ERROR -> NativeProcessOutcome.INTERNAL_ERROR
        else -> null
    }

    private fun internalError(message: String): NativeProcessResult =
        NativeProcessResult.internalError(message)

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

        /** Reads one little-endian signed i32, or null when fewer than 4 bytes remain. */
        fun readI32(): Int? {
            if (remaining < 4) return null
            var value = 0
            for (i in 0 until 4) {
                value = value or ((bytes[position + i].toInt() and 0xFF) shl (8 * i))
            }
            position += 4
            return value
        }

        /** Reads one length-prefixed string, or null when malformed. */
        fun readUtf8String(): String? {
            val length = readU32() ?: return null
            if (length > remaining.toLong()) return null
            val text = String(bytes, position, length.toInt(), Charsets.UTF_8)
            position += length.toInt()
            return text
        }
    }
}
