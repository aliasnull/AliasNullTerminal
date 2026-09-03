package app.aliasnull.shell.runtime

/**
 * Honest, high-level availability of the AliasNull execution runtime.
 *
 * The states distinguish three genuinely different things so no layer may claim a
 * Linux runtime is ready before one exists:
 *
 *  - whether a native bootstrap foundation is connected ([FrontendOnly] vs
 *    [NativeBootstrapReady] with [Initializing]/[Error] in between), and
 *  - whether a real AliasNull execution runtime is running ([Ready]/[Stopped]).
 *
 * [NativeBootstrapReady] means the native library loaded, the runtime-owned
 * directories were validated and the native bootstrap layer reported success. It
 * is deliberately NOT [Ready]: no command execution backend, PTY or Linux
 * userspace exists yet, so the temporary frontend executor still answers
 * commands. [Ready] and [Stopped] are reserved for the future real runtime and
 * must only be reported once that runtime genuinely exists and is running
 * (respectively, has genuinely stopped). No temporary frontend backend may ever
 * advertise [Ready].
 */
enum class ShellRuntimeState {

    /** No AliasNull native layer is connected; only the temporary frontend executor responds. */
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
