//! AliasNull AN Shell core (Rust).
//!
//! Part 27-B established this crate as a real, buildable Android ARM64 cdylib
//! dependency of the app. Part 27-C added the source-input model (byte-offset
//! spans) and a small deterministic lexer. Part 27-D added the first syntax
//! layer on top: a parser that turns the lexer's token stream into a minimal
//! AN Shell AST. Part 27-E adds the semantic layer: an `analyze` entry point
//! that interprets a parsed `Program` into typed, recognised built-in commands
//! or a `SemanticError`.
//!
//! This is language-core only. It is NOT a shell, NOT a command executor, NOT
//! a parser/executor hybrid, and it is not connected to the Android command
//! system in any way:
//!
//! * it does not execute commands, spawn processes, open a PTY or touch JNI;
//! * it is not ShellCommandExecutor, ExecutionRouter, TerminalSessionEngine or
//!   TerminalSessionOrchestrator; and
//! * nothing in the Android app calls into it yet (the .so is packaged but not
//!   loaded, and NativeRuntimeBridge remains the sole Kotlin/JNI owner).
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
//!   BuiltinCommandKind).

mod ast;
mod lexer;
mod parser;
mod semantic;
mod source;
mod token;

pub use ast::{Argument, Command, Program};
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
