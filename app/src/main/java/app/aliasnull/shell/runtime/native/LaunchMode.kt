package app.aliasnull.shell.runtime.native

/**
 * How a [NativeProcessRequest] is to be executed by the native one-shot runner
 * (Part 27-S2).
 *
 * [DIRECT] is the ordinary mode: the native runner execve()s [argv]`[0]`
 * directly as the program. Every host binary self-check request uses it.
 *
 * [LINKER_LAUNCH] is the defined launch mode for an ELF that Android will not
 * execve() directly from app-private storage (an SELinux app_data_file has no
 * execute_no_trans right), but which the system dynamic linker may load and run:
 * the request argv is
 *   ["/system/bin/linker64", "<verified executable absolute path>"]
 * so the native runner (unchanged) execve()s /system/bin/linker64 - a system
 * binary whose direct exec is proven allowed - and the linker then loads and
 * runs the verified ELF as the child. This is a permanent, deliberate mode, not
 * a fallback: no request ever tries DIRECT first and then "retries" via the
 * linker.
 *
 * A request's [NativeProcessRequest.launchMode] must be consistent with its
 * argv, and [app.aliasnull.shell.runtime.NativeExecutionPolicy] enforces that
 * consistency: [app.aliasnull.shell.runtime.NativeExecutionPolicy.decide] allows
 * only DIRECT requests whose argv is one of the fixed internal invocations, and
 * [app.aliasnull.shell.runtime.NativeExecutionPolicy.decideBaseExecutable] is
 * the only decision that allows a LINKER_LAUNCH request, for exactly the single
 * verified bundled base executable. LINKER_LAUNCH therefore never becomes a
 * generic "run any file through linker64" facility.
 */
enum class LaunchMode {
    /** Execve the request's argv[0] directly as the program. */
    DIRECT,

    /**
     * Execve the system dynamic linker (the policy's fixed path
     * `/system/bin/linker64`) with the verified target ELF as its single
     * program argument.
     */
    LINKER_LAUNCH,
}
