package app.aliasnull.shell.runtime

/**
 * Honest, high-level availability of the AliasNull native (C++) execution
 * runtime foundation.
 *
 * This is deliberately a SEPARATE axis from the Shell's command backend. The
 * Shell gate is [ShellBackendState], which is decided by the AN Shell core bridge
 * ([app.aliasnull.shell.runtime.native.AnShellCoreBridge]) and is the only thing
 * that says whether the Shell may execute commands. This enum instead tracks the
 * C++ native-foundation lifecycle below it, which never executes a command:
 *
 *  - whether the native bootstrap foundation is connected ([FrontendOnly] vs
 *    [NativeBootstrapReady] with [Initializing]/[Error] in between), and
 *  - whether a real AliasNull execution runtime is running ([Ready]/[Stopped]).
 *
 * [NativeBootstrapReady] means the native library loaded, the runtime-owned
 * directories were validated and the native bootstrap layer reported success. It
 * is deliberately NOT [Ready]: no command execution backend, PTY or Linux
 * userspace exists yet, and reaching it does not by itself move [ShellBackendState].
 * [Ready] and [Stopped] are reserved for the future real runtime and must only be
 * reported once that runtime genuinely exists and is running (respectively, has
 * genuinely stopped).
 */
enum class ShellRuntimeState {

    /** No AliasNull native (C++) foundation is connected yet. */
    FrontendOnly,

    /** A native bootstrap or the future real runtime is being brought up. */
    Initializing,

    /** The native bootstrap foundation initialized successfully (not a command runtime). */
    NativeBootstrapReady,

    /** A real AliasNull execution runtime is ready to run commands (future). */
    Ready,

    /** A real AliasNull execution runtime has been stopped (future). */
    Stopped,

    /** A native bootstrap or the future runtime failed to start. */
    Error,
}
