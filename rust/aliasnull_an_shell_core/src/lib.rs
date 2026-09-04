//! AliasNull AN Shell core (Rust).
//!
//! Part 27-B established this crate as a real, buildable Android ARM64 cdylib
//! dependency of the app. Part 27-C added the source-input model (byte-offset
//! spans) and a small deterministic lexer. Part 27-D added the first syntax
//! layer on top: a parser that turns the lexer's token stream into a minimal
//! AN Shell AST. Part 27-E adds the semantic layer: an `analyze` entry point
//! that interprets a parsed `Program` into typed, recognised built-in commands
//! or a `SemanticError`. Part 27-F adds the execution core: `execute_builtin`
//! runs a recognised built-in's deterministic in-memory semantics and returns
//! an `ExecutionResult`. Part 27-G adds the first real Kotlin <-> Rust
//! boundary: `bridge` owns the pure "run one command string through the whole
//! pipeline and encode the outcome" logic, and `ffi` exposes the two JNI
//! functions (`nativeApiVersion`, `nativeExecuteCommand`) that the app's
//! dedicated Kotlin bridge object calls.
//!
//! This is language-core only. It is NOT a shell, NOT a process launcher, NOT
//! a PTY or terminal emulator, and it is NOT connected to the Android *command
//! system*:
//!
//! * its only "execution" is the deterministic in-memory semantics of the
//!   four supported built-ins (help, about, clear, echo); it does not spawn
//!   processes, open a PTY or reach into a Linux runtime;
//! * it is not ShellCommandExecutor, ExecutionRouter, TerminalSessionEngine or
//!   TerminalSessionOrchestrator, and Part 27-G does not route a single command
//!   to it (the temporary frontend executor remains the visible command path);
//! * since Part 27-G the packaged `.so` IS loaded by Kotlin -- but only through
//!   the dedicated `AnShellCoreNativeBridge` JNI owner, never from the UI, the
//!   ViewModel or a command handler. The C++ `NativeRuntimeBridge` remains the
//!   sole Kotlin/JNI owner of the separate `libaliasnull_runtime.so`.
//!
//! Public surface exposed by this crate:
//!
//! * source: SourceText (owning input) and byte-offset SourceSpan;
//! * token:  the minimal TokenKind / Token vocabulary;
//! * lexer:  lex(), turning a SourceText into `Result<Vec<Token>, LexerError>`;
//! * ast:    the minimal Program / Command / Argument syntax AST;
//! * parser: parse(), turning a `&[Token]` into `Result<Program, ParseError>`;
//! * semantic: analyze(), turning a `&Program` into `Result<SemanticProgram,
//!   SemanticError>` over the recognised built-in vocabulary (BuiltinCommand /
//!   BuiltinCommandKind);
//! * execution: execute_builtin(), turning a `&BuiltinCommand` into an
//!   `ExecutionResult` (output units plus an optional clear request);
//! * bridge (internal): run_command() drives the full pipeline for one command
//!   string; encode_outcome() serialises the outcome into the Kotlin payload.
//! * ffi (internal): the two JNI functions Kotlin loads and calls. The single
//!   pre-existing plain-C identity export `aliasnull_an_shell_core_api_version`
//!   is unchanged and is the source of truth the JNI `nativeApiVersion` wraps.

mod ast;
mod bridge;
mod execution;
mod ffi;
mod lexer;
mod parser;
mod semantic;
mod source;
mod token;

pub use ast::{Argument, Command, Program};
pub use execution::{execute_builtin, ExecutionResult};
pub use lexer::{lex, LexerError};
pub use parser::{parse, ParseError, ParseErrorKind};
pub use semantic::{
    analyze, BuiltinCommand, BuiltinCommandKind, SemanticError, SemanticErrorKind,
    SemanticProgram,
};
pub use source::{SourceSpan, SourceText};
pub use token::{Token, TokenKind};

/// Returns the AliasNull AN Shell core API version as a `0x00MMmmpp`-style
/// constant (this build: 0.1.0). Exported so the linked artifact carries a
/// stable identity a future caller can verify. Part 27-B established this ABI;
/// it is preserved and unchanged. Nothing calls it yet.
#[no_mangle]
pub extern "C" fn aliasnull_an_shell_core_api_version() -> u32 {
    0x0000_0100
}
