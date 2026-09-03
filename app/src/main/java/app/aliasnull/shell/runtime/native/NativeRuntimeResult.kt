package app.aliasnull.shell.runtime.native

/**
 * Outcome codes of a native bootstrap attempt, shared between the loader, the
 * storage-preparation step and the native initialization call. Distinct from
 * [success] only through [NativeRuntimeResult] so a caller (currently
 * ShellRuntimeManager) can tell WHY an attempt did not reach bootstrap, instead
 * of collapsing every failure into one boolean.
 */
enum class NativeBootstrapCode {
    /** Native bootstrap completed successfully. */
    OK,

    /** libaliasnull_runtime.so could not be loaded (missing or unsupported ABI). */
    LIBRARY_LOAD_FAILED,

    /** The runtime root supplied for validation was empty or not absolute. */
    RUNTIME_ROOT_INVALID,

    /** The runtime root path is not a directory. */
    RUNTIME_ROOT_NOT_DIRECTORY,

    /** A runtime-owned subdirectory could not be created/confirmed. */
    RUNTIME_DIRECTORY_MISSING,

    /** A runtime-owned subdirectory exists but is not writable. */
    RUNTIME_DIRECTORY_NOT_WRITABLE,

    /** The native layer refused or failed the bootstrap (native error). */
    NATIVE_INIT_FAILED,

    /** Bootstrap was requested after the native layer was shut down. */
    NATIVE_ALREADY_SHUTDOWN,

    /** An unexpected exception surfaced outside the expected failure paths. */
    UNEXPECTED,
}

/**
 * Structured, honest outcome of one native bootstrap attempt. [success] is true
 * only when the library loaded, the runtime directories were prepared and the
 * native layer reported a successful bootstrap. The metadata fields are only
 * populated on success and only ever carry AliasNull's own version/capability
 * strings - never fabricated Linux distribution or kernel information.
 */
data class NativeRuntimeResult(
    val success: Boolean,
    val code: NativeBootstrapCode,
    val message: String,
    val runtimeVersion: String = "",
    val bootstrapVersion: String = "",
    val capabilities: List<String> = emptyList(),
) {
    companion object {
        fun failure(code: NativeBootstrapCode, message: String) =
            NativeRuntimeResult(success = false, code = code, message = message)

        fun unexpected(error: Throwable) =
            failure(
                NativeBootstrapCode.UNEXPECTED,
                "Native runtime bootstrap error: ${error.message ?: error::class.simpleName ?: "unknown"}",
            )
    }
}
