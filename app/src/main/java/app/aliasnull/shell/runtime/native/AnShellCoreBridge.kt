package app.aliasnull.shell.runtime.native

import android.util.Log

/**
 * Lifecycle state of the AN Shell core bridge -- the Kotlin <-> Rust boundary
 * for libaliasnull_an_shell_core.so -- reported honestly so a caller never has
 * to guess why a command could not reach the native core.
 */
enum class AnShellCoreBridgeState {
    /** No load or handshake attempt has been made yet. */
    NOT_ATTEMPTED,

    /** The library loaded and the API version handshake matched. */
    READY,

    /** The library could not be loaded (missing .so or unsupported ABI). */
    LOAD_FAILED,

    /** The library loaded but reported a different API version than expected. */
    VERSION_MISMATCH,
}

/**
 * Structured status of the AN Shell core bridge after its latest attempt.
 *
 * [state] says what the bridge currently is. [apiVersion] is the API version the
 * native core reported during the handshake (null until a handshake succeeds).
 * [message] explains the state in human terms.
 */
data class AnShellCoreBridgeStatus(
    val state: AnShellCoreBridgeState,
    val apiVersion: Int? = null,
    val message: String,
) {
    /** True only while the bridge is [AnShellCoreBridgeState.READY]. */
    val canExecute: Boolean
        get() = state == AnShellCoreBridgeState.READY
}

/**
 * The app-facing abstraction over the AN Shell core native boundary.
 *
 * This object is the single Kotlin surface that loads
 * libaliasnull_an_shell_core.so (through [AnShellCoreNativeBridge]), verifies
 * its API version against [EXPECTED_AN_SHELL_CORE_API_VERSION] and executes one
 * command string through the whole native pipeline (lex -> parse -> analyze ->
 * execute) by delegating to the [AnShellCoreNativeBridge] JNI owner and decoding
 * the payload with [AnShellCorePayloadCodec]. It hides every JNI name and raw
 * byte[] from its callers.
 *
 * Deliberate scope: this is a language-core bridge, NOT a command backend. It is
 * not a ShellCommandExecutor, is not registered with the execution router, and
 * AUTO never routes a command to it; the temporary frontend executor remains the
 * only command path. Nothing in the UI or a ViewModel calls this object -- the
 * runtime layer may probe it observationally (see AliasNullRuntimeManager), and
 * nothing sends a user-typed command through it yet.
 *
 * The handshake value expected here must stay in lock-step with the Rust
 * constant `aliasnull_an_shell_core_api_version()`; 0.1.0 is 0x0000_0100.
 */
object AnShellCoreBridge {

    private const val TAG = "AnShellCoreBridge"

    /** Native core API version this build expects (0.1.0). */
    const val EXPECTED_AN_SHELL_CORE_API_VERSION: Int = 0x0000_0100

    @Volatile
    private var status: AnShellCoreBridgeStatus = AnShellCoreBridgeStatus(
        state = AnShellCoreBridgeState.NOT_ATTEMPTED,
        message = "The AN Shell core bridge has not been verified yet.",
    )

    /** The latest bridge status; [AnShellCoreBridgeState.NOT_ATTEMPTED] until the first [verify]. */
    fun currentStatus(): AnShellCoreBridgeStatus = status

    /**
     * Loads the native core exactly once and runs the version handshake, then
     * returns an honest status. Safe to call repeatedly and from any thread; a
     * failed attempt never throws and reports a structured state instead.
     */
    fun verify(): AnShellCoreBridgeStatus {
        if (status.state == AnShellCoreBridgeState.READY) return status
        synchronized(this) {
            if (status.state == AnShellCoreBridgeState.READY) return status

            if (!AnShellCoreNativeBridge.ensureLibraryLoaded()) {
                status = AnShellCoreBridgeStatus(
                    state = AnShellCoreBridgeState.LOAD_FAILED,
                    message = "libaliasnull_an_shell_core.so could not be loaded; the AN Shell core is unavailable.",
                )
                Log.e(TAG, "AN Shell core bridge state -> LOAD_FAILED: ${status.message}")
                return status
            }

            val apiVersion = AnShellCoreNativeBridge.readApiVersion()
            if (apiVersion == null) {
                status = AnShellCoreBridgeStatus(
                    state = AnShellCoreBridgeState.LOAD_FAILED,
                    message = "The library loaded but its API version could not be read; the AN Shell core is unusable.",
                )
                Log.e(TAG, "AN Shell core bridge state -> LOAD_FAILED: ${status.message}")
                return status
            }

            if (apiVersion != EXPECTED_AN_SHELL_CORE_API_VERSION) {
                status = AnShellCoreBridgeStatus(
                    state = AnShellCoreBridgeState.VERSION_MISMATCH,
                    apiVersion = apiVersion,
                    message = "The native core reports API version ${apiVersionHex(apiVersion)} " +
                        "but this build expects ${apiVersionHex(EXPECTED_AN_SHELL_CORE_API_VERSION)}.",
                )
                Log.e(TAG, "AN Shell core bridge state -> VERSION_MISMATCH: ${status.message}")
                return status
            }

            status = AnShellCoreBridgeStatus(
                state = AnShellCoreBridgeState.READY,
                apiVersion = apiVersion,
                message = "The AN Shell core is ready (native API version ${apiVersionHex(apiVersion)}).",
            )
            Log.i(TAG, "AN Shell core bridge state -> READY (${apiVersionHex(apiVersion)})")
            return status
        }
    }

    /**
     * Sends one command string through the native language core and returns the
     * structured result. Deterministic for a given string: the core pipeline has
     * no side effects (no process, PTY, filesystem, environment or network).
     *
     * When the bridge is not [AnShellCoreBridgeState.READY] the command is not
     * sent at all and the result is [AnShellCoreResultKind.BRIDGE_UNAVAILABLE]. A
     * null payload from the JNI layer (the only case the native side returns
     * null) is mapped to a structured [AnShellCoreResultKind.INTERNAL_ERROR].
     */
    fun execute(command: String): AnShellCoreExecutionResult {
        val ready = verify()
        if (ready.state != AnShellCoreBridgeState.READY) {
            return AnShellCoreExecutionResult.bridgeUnavailable(
                "The command was not sent to the AN Shell core: ${ready.message}",
            )
        }
        val payload = AnShellCoreNativeBridge.executeCommandBytes(command.toByteArray(Charsets.UTF_8))
        if (payload == null) {
            return AnShellCoreExecutionResult.pipelineError(
                kind = AnShellCoreResultKind.INTERNAL_ERROR,
                message = "The native core could not produce a result payload for this command.",
            )
        }
        return AnShellCorePayloadCodec.decode(payload)
    }

    /** Formats an API version as an 8-hex-digit label for logs and messages. */
    private fun apiVersionHex(version: Int): String =
        "0x" + version.toString(16).padStart(8, '0')
}
