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
 */
data class NativeProcessRequest(
    val argv: List<String>,
    val workingDirectory: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val stdinBytes: ByteArray? = null,
) {
    /** Returns null when the request is usable, otherwise a human reason it is not. */
    fun validationError(): String? {
        if (argv.isEmpty()) return "The process request has an empty argv."
        if (argv[0].isEmpty()) return "The process request has an empty executable name."
        for ((key, _) in environment) {
            if (key.isEmpty()) return "An environment key is empty."
            if (key.contains('=')) return "An environment key contains '='."
        }
        return null
    }
}
