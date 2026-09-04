//! AliasNull AN Shell core foundation (Rust) - Part 27-B bootstrap.
//!
//! This crate is the smallest genuine Rust artifact on the Android ARM64 build
//! path. It is a BUILD TOOLCHAIN bootstrap, NOT a shell and NOT a command
//! executor. It deliberately contains:
//!
//! * no AN command syntax, lexer, parser, expansion, pipes or redirects,
//! * no process / PTY / fork / exec and no Linux runtime,
//! * no filesystem, no `.anpkg`, no ALIA package logic,
//! * no JNI and no Kotlin-visible call surface, and no `System.loadLibrary` of
//!   its own (the existing `NativeRuntimeBridge` remains the sole Kotlin/JNI
//!   owner of the native boundary).
//!
//! Compiling this crate successfully does NOT mean a terminal, process or Linux
//! runtime exists, and it enables no capability flags. The crate only fixes the
//! ownership boundary for future shell-core work: a later milestone can reach
//! into this library through the existing native boundary.

/// Returns the AliasNull AN Shell core API version as a `0x00MMmmpp`-style
/// constant (this build: 0.1.0). Exported so the linked artifact carries a
/// stable identity a future caller can verify; nothing calls it yet, which is
/// intentional and honest for a toolchain-bootstrap milestone.
#[no_mangle]
pub extern "C" fn aliasnull_an_shell_core_api_version() -> u32 {
    0x0000_0100
}
