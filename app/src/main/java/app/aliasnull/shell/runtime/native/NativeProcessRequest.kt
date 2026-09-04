package app.aliasnull.shell.runtime.native

/**
 * A real one-shot external-process execution request (Part 27-O).
 *
 * [argv] is the single authority for what runs: the runner is handed a fully
 * formed argument vector and never a shell command string, and AliasNull parses
 * no shell language here. [argv] must be non-empty and [argv]`[0]` (the
 * executable) must be non-empty; a bare executable name (no '/') is resolved
 * along PATH by the native runner, and a name containing '/' is used as-is.
 *
 * [workingDirectory], when set, becomes the child's working directory. The child
 * otherwise inherits this process's working directory.
 *
 * [environment] holds optional KEY -> VALUE overrides applied over the inherited
 * environment. A value may contain '='; a key must not.
 *
 * [stdinBytes], when non-null, is written to the child's stdin exactly once
 * before the pipe is closed. Null or empty both mean the child sees end-of-file
 * on stdin. This is a one-shot payload: interactive/live stdin is not part of
 * Part 27-O.
 *
 * [launchMode] declares how [argv] is to be executed and defaults to
 * [LaunchMode.DIRECT]. The argv remains the single authority for what the native
 * runner execve()s: for [LaunchMode.DIRECT], argv[0] is the program; for
 * [LaunchMode.LINKER_LAUNCH], argv is the exact host argv
 * `[linker64, "<verified executable path>"]`. The mode is not a separate trust
 * input - NativeExecutionPolicy rejects any request whose mode is not consistent
 * with its argv, so a request can never declare one mode while smuggling in
 * another.
 */
data class NativeProcessRequest(
    val argv: List<String>,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdinBytes: ByteArray? = null,
    val launchMode: LaunchMode = LaunchMode.DIRECT,
) {
    /** Returns null when the request is usable, otherwise a human reason it is not. */
    fun validationError(): String? {
        if (argv.isEmpty()) return "The process request has an empty argv."
        if (argv[0].isEmpty()) return "The process request has an empty executable name."
        if (launchMode == LaunchMode.LINKER_LAUNCH && argv.size < 2) {
            return "A linker-launched request needs the linker host argv[0] and a target program argv[1]."
        }
        for ((key, _) in environment) {
            if (key.isEmpty()) return "An environment key is empty."
            if (key.contains('=')) return "An environment key contains '='."
        }
        return null
    }
}
