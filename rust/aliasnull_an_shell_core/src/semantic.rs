//! The Part 27-E AN Shell semantic layer: Program AST -> recognised built-ins.
//!
//! This module interprets a parsed `Program` syntactically/semantically: it
//! decides which commands name one of the real AN Shell built-ins and preserves
//! everything else that the execution stage would need. It is a pure,
//! dependency-free function over the AST -- `analyze` reads a `Program` and
//! returns either a typed `SemanticProgram` of recognised built-ins or a
//! `SemanticError`.
//!
//! Strict non-execution boundary
//! -----------------------------
//! Nothing here executes, prints, spawns a process, opens a PTY, touches the
//! environment or the filesystem, or reaches JNI/Kotlin. This module is a
//! classification and span-preservation layer only. It produces data; running
//! the commands is out of scope for this crate entirely.

use crate::ast::{Argument, Command, Program};
use crate::source::SourceSpan;

/// A recognised AN Shell built-in command name.
///
/// This is the AN Shell core's own command vocabulary (the sole command
/// backend): a command whose name resolves to one
/// of these kinds is a built-in; any other name is an `UnknownCommand` error.
/// The kinds carry no behaviour -- they are tags, not handlers.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum BuiltinCommandKind {
    /// The `help` command.
    Help,
    /// The `about` command.
    About,
    /// The `clear` command.
    Clear,
    /// The `echo` command (the only vocabulary member that consumes its
    /// arguments).
    Echo,
}

impl BuiltinCommandKind {
    /// Resolves a command *name* to its built-in kind, or `None` when the name
    /// does not match any built-in.
    ///
    /// Case behaviour: the name is matched case-insensitively against the
    /// literal set `{help, about, clear, echo}`.
    /// The whole vocabulary is ASCII, so ASCII case-insensitive comparison is
    /// exactly equivalent to full-Unicode `lowercase()` for
    /// every name that could possibly equal one of these four literals.
    pub fn from_name(name: &str) -> Option<Self> {
        if name.eq_ignore_ascii_case("help") {
            Some(BuiltinCommandKind::Help)
        } else if name.eq_ignore_ascii_case("about") {
            Some(BuiltinCommandKind::About)
        } else if name.eq_ignore_ascii_case("clear") {
            Some(BuiltinCommandKind::Clear)
        } else if name.eq_ignore_ascii_case("echo") {
            Some(BuiltinCommandKind::Echo)
        } else {
            None
        }
    }
}

/// One command recognised as a built-in, after semantic analysis.
///
/// The first argument of the source command was its name; every remaining
/// argument is preserved unchanged as an `Argument` (Word vs String kind and
/// source span intact). No shell words here are interpreted or expanded -- if
/// the lexer kept metacharacters as word content, they stay word content.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct BuiltinCommand {
    /// Which built-in this command named.
    pub kind: BuiltinCommandKind,
    /// Source region of the name token. It locates the *source text* of the
    /// name (with its original casing), not the resolved kind.
    pub name_span: SourceSpan,
    /// The arguments after the name, in source order, spans preserved.
    /// Empty when the built-in took no arguments. Owned copies of the AST
    /// arguments; `analyze` never mutates the input `Program`.
    pub arguments: Vec<Argument>,
    /// Source region from the name token's start to the last argument's end.
    /// Identical to the source `Command`'s span.
    pub span: SourceSpan,
}

/// The result of analyzing a whole `Program`: its recognised built-ins.
///
/// Every command in `commands` resolved successfully; analysis stops at the
/// first failing command and returns a `SemanticError` instead, so a
/// `SemanticProgram` never holds a partial or unknown command.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SemanticProgram {
    /// The recognised built-in commands, in source order.
    pub commands: Vec<BuiltinCommand>,
    /// Source region of the whole analyzed program.
    ///
    /// Span policy mirrors `Program`: `[0, 0)` when `commands` is empty,
    /// otherwise the first command's start through the last command's end.
    pub span: SourceSpan,
}

/// The kind of a semantic-analysis failure.
///
/// Only one kind is reachable through the real lex -> parse -> analyze
/// pipeline: `UnknownCommand`. `EmptyCommand` is a defensive case for a
/// hand-built AST, since `Program` and `Command` are publicly constructible;
/// the parser never produces a `Command` with no arguments.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SemanticErrorKind {
    /// The command's name does not match any built-in. Also produced when the
    /// name position holds a quoted string rather than a bare word, because
    /// the core resolves a name only from a bare word and never strips quotes
    /// before comparing (see the module documentation on name resolution).
    UnknownCommand,
    /// A command had no arguments at all, so it has no name to resolve.
    /// Unreachable from a parsed `Program`.
    EmptyCommand,
}

/// A semantic-analysis failure with enough source information to locate it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct SemanticError {
    /// Which semantic rule failed.
    pub kind: SemanticErrorKind,
    /// The offending region. For `UnknownCommand` it is the name token's span;
    /// for `EmptyCommand` it is the whole empty command's span.
    pub span: SourceSpan,
}

impl SemanticError {
    /// Creates a semantic-analysis error.
    pub const fn new(kind: SemanticErrorKind, span: SourceSpan) -> Self {
        SemanticError { kind, span }
    }
}

impl core::fmt::Display for SemanticError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self.kind {
            SemanticErrorKind::UnknownCommand => {
                write!(
                    f,
                    "unknown command: no built-in matches this name at byte {}",
                    self.span.start
                )
            }
            SemanticErrorKind::EmptyCommand => {
                write!(
                    f,
                    "empty command has no name to resolve at byte {}",
                    self.span.start
                )
            }
        }
    }
}

impl std::error::Error for SemanticError {}

/// Analyzes a parsed `Program` into recognised built-ins.
///
/// Name resolution
/// ---------------
/// The *name* of a command is its first argument, and it must be a bare
/// (`Word`) argument:
///
/// * a quoted first argument (an `Argument::String`) is never resolved as a
///   name: the lexer preserves the surrounding quote characters inside the raw
///   first token, so the core sees them in the name position and reports the
///   command as unknown. Accepting `"echo"` here because
///   its interpreted value happens to be `echo` would silently change product
///   behaviour, so `analyze` reports `UnknownCommand` at the quoted token.
/// * a bare name is resolved case-insensitively against the built-in
///   vocabulary via [`BuiltinCommandKind::from_name`]; a bare name with no
///   match is `UnknownCommand` at that word's span.
///
/// Commands are analyzed in source order and analysis fails fast: the first
/// command that does not resolve returns an error, and no later command is
/// examined. There is no recovery, no "best effort" guess and no partial
/// `SemanticProgram` on error.
///
/// Argument handling
/// -----------------
/// The name is consumed; every later argument is preserved verbatim (its kind,
/// value and span) as `BuiltinCommand::arguments`. No argument list is
/// validated against its built-in -- arity and echo-printing rules are an
/// execution concern, not this crate's.
///
/// Spans
/// -----
/// All spans are the existing byte-offset, half-open `[start, end)` spans.
/// `BuiltinCommand::name_span` is exactly the name token's span;
/// `BuiltinCommand::span` is exactly the source `Command`'s span (name start
/// through last argument end); `SemanticProgram::span` is exactly the source
/// `Program`'s span.
pub fn analyze(program: &Program) -> Result<SemanticProgram, SemanticError> {
    let mut commands = Vec::with_capacity(program.commands.len());

    for command in &program.commands {
        commands.push(analyze_command(command)?);
    }

    Ok(SemanticProgram { commands, span: program.span })
}

/// Analyzes one `Command`. Private: only `analyze` drives it.
fn analyze_command(command: &Command) -> Result<BuiltinCommand, SemanticError> {
    // The name is the first argument. Split the arguments so the iterator
    // borrow stays independent of the head match below.
    let mut words = command.words.iter();
    let Some(first) = words.next() else {
        // Unreachable through the parser (a command always has arguments),
        // but `Command` is public and hand-buildable.
        return Err(SemanticError::new(SemanticErrorKind::EmptyCommand, command.span));
    };

    let (kind, name_span) = match first {
        // Only a bare word can name a built-in.
        Argument::Word { value, span } => {
            match BuiltinCommandKind::from_name(value) {
                Some(kind) => (kind, *span),
                None => return Err(SemanticError::new(SemanticErrorKind::UnknownCommand, *span)),
            }
        }
        // A quoted first argument: the surrounding quotes sit in the raw name
        // token, so this never resolves to a built-in.
        Argument::String { span, .. } => {
            return Err(SemanticError::new(SemanticErrorKind::UnknownCommand, *span));
        }
    };

    let arguments: Vec<Argument> = words.cloned().collect();
    Ok(BuiltinCommand { kind, name_span, arguments, span: command.span })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ast::Argument;
    use crate::lexer::lex;
    use crate::parser::parse;
    use crate::source::SourceText;

    /// Runs the real lex -> parse -> analyze pipeline over `source`.
    fn analyze_source(source: &str) -> Result<SemanticProgram, SemanticError> {
        let tokens = lex(&SourceText::new(source)).expect("lexer accepted this input");
        let program = parse(&tokens).expect("parser accepted this input");
        analyze(&program)
    }

    fn word_arg(value: &str, span: SourceSpan) -> Argument {
        Argument::Word { value: value.to_string(), span }
    }

    #[test]
    fn empty_program_analyzes_to_empty_semantic_program() {
        let program = Program { commands: vec![], span: SourceSpan::new(0, 0) };
        let semantic = analyze(&program).unwrap();
        assert!(semantic.commands.is_empty());
        assert_eq!(semantic.span, SourceSpan::new(0, 0));
    }

    #[test]
    fn empty_source_analyzes_to_empty_semantic_program() {
        let semantic = analyze_source("").unwrap();
        assert!(semantic.commands.is_empty());
        assert_eq!(semantic.span, SourceSpan::new(0, 0));
    }

    #[test]
    fn all_four_builtins_resolve_to_their_kind() {
        let source = "help\nabout\nclear\necho";
        let semantic = analyze_source(source).unwrap();
        let kinds: Vec<BuiltinCommandKind> =
            semantic.commands.iter().map(|c| c.kind).collect();
        assert_eq!(
            kinds,
            vec![
                BuiltinCommandKind::Help,
                BuiltinCommandKind::About,
                BuiltinCommandKind::Clear,
                BuiltinCommandKind::Echo,
            ]
        );
        // Spans: each builtin is its own line; name spans are the line spans.
        assert_eq!(semantic.commands[0].name_span, SourceSpan::new(0, 4));
        assert_eq!(semantic.commands[1].name_span, SourceSpan::new(5, 10));
        assert_eq!(semantic.commands[2].name_span, SourceSpan::new(11, 16));
        assert_eq!(semantic.commands[3].name_span, SourceSpan::new(17, 21));
        assert_eq!(semantic.span, SourceSpan::new(0, 21));
    }

    #[test]
    fn name_only_builtin_has_no_arguments() {
        let semantic = analyze_source("clear").unwrap();
        assert_eq!(semantic.commands.len(), 1);
        let command = &semantic.commands[0];
        assert_eq!(command.kind, BuiltinCommandKind::Clear);
        assert!(command.arguments.is_empty());
        // name span, command span and program span all cover [0, 5).
        assert_eq!(command.name_span, SourceSpan::new(0, 5));
        assert_eq!(command.span, SourceSpan::new(0, 5));
        assert_eq!(semantic.span, SourceSpan::new(0, 5));
    }

    #[test]
    fn echo_preserves_word_arguments_and_spans() {
        // "echo hello world": echo[0,4) hello[5,10) world[11,16).
        let semantic = analyze_source("echo hello world").unwrap();
        assert_eq!(semantic.commands.len(), 1);
        let command = &semantic.commands[0];
        assert_eq!(command.kind, BuiltinCommandKind::Echo);
        assert_eq!(command.name_span, SourceSpan::new(0, 4));
        assert_eq!(
            command.arguments,
            vec![
                word_arg("hello", SourceSpan::new(5, 10)),
                word_arg("world", SourceSpan::new(11, 16)),
            ]
        );
        assert_eq!(command.span, SourceSpan::new(0, 16));
        assert_eq!(semantic.span, SourceSpan::new(0, 16));
    }

    #[test]
    fn echo_preserves_quoted_argument_value_and_span() {
        // "echo \"hello world\"": the string token spans [5, 18) quotes
        // included, its value is the interpreted content without quotes.
        let semantic = analyze_source("echo \"hello world\"").unwrap();
        assert_eq!(semantic.commands.len(), 1);
        let command = &semantic.commands[0];
        assert_eq!(command.kind, BuiltinCommandKind::Echo);
        assert_eq!(command.name_span, SourceSpan::new(0, 4));
        assert_eq!(command.arguments.len(), 1);
        assert!(matches!(&command.arguments[0], Argument::String { .. }));
        assert_eq!(command.arguments[0].value(), "hello world");
        assert_eq!(command.arguments[0].span(), SourceSpan::new(5, 18));
        assert_eq!(command.span, SourceSpan::new(0, 18));
    }

    #[test]
    fn echo_mixed_argument_kinds_preserve_each_origin() {
        let semantic = analyze_source("echo one \"two words\" three").unwrap();
        let command = &semantic.commands[0];
        assert_eq!(command.arguments.len(), 3);
        assert!(matches!(&command.arguments[0], Argument::Word { .. }));
        assert!(matches!(&command.arguments[1], Argument::String { .. }));
        assert!(matches!(&command.arguments[2], Argument::Word { .. }));
        assert_eq!(command.arguments[0].value(), "one");
        assert_eq!(command.arguments[1].value(), "two words");
        assert_eq!(command.arguments[2].value(), "three");
    }

    #[test]
    fn recognized_builtin_names_are_case_insensitive() {
        for (source, expected) in [
            ("HELP", BuiltinCommandKind::Help),
            ("About", BuiltinCommandKind::About),
            ("cLeAr", BuiltinCommandKind::Clear),
            ("ECHO", BuiltinCommandKind::Echo),
        ] {
            let semantic = analyze_source(source).unwrap();
            assert_eq!(semantic.commands.len(), 1, "for {source:?}");
            assert_eq!(semantic.commands[0].kind, expected, "for {source:?}");
        }
    }

    #[test]
    fn name_span_locates_original_source_casing() {
        // The name token span covers the source as written, not the resolved
        // kind or a canonical spelling.
        let semantic = analyze_source("HELP").unwrap();
        assert_eq!(semantic.commands[0].name_span, SourceSpan::new(0, 4));
    }

    #[test]
    fn argument_values_keep_original_case() {
        let semantic = analyze_source("Echo HeLLo WoRlD").unwrap();
        let command = &semantic.commands[0];
        assert_eq!(command.kind, BuiltinCommandKind::Echo);
        assert_eq!(command.arguments[0].value(), "HeLLo");
        assert_eq!(command.arguments[1].value(), "WoRlD");
        // "Echo HeLLo WoRlD" is 16 bytes.
        assert_eq!(command.name_span, SourceSpan::new(0, 4));
        assert_eq!(command.arguments[0].span(), SourceSpan::new(5, 10));
        assert_eq!(command.arguments[1].span(), SourceSpan::new(11, 16));
    }

    #[test]
    fn unknown_bare_name_is_an_error_at_the_name_span() {
        let err = analyze_source("foobar").unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(err.span, SourceSpan::new(0, 6));
    }

    #[test]
    fn unknown_builtin_like_name_is_still_unknown() {
        // "helpo" is not the vocabulary; case folding must not over-match.
        let err = analyze_source("helpo").unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(err.span, SourceSpan::new(0, 5));
    }

    #[test]
    fn quoted_first_argument_is_not_a_builtin_name() {
        // The quotes sit in the raw name token, so `"echo"` never resolves to
        // Echo even though its interpreted value is "echo".
        let err = analyze_source("\"echo\"").unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(err.span, SourceSpan::new(0, 6));
    }

    #[test]
    fn quoted_builtin_name_among_arguments_is_also_unknown() {
        // Same rule with trailing arguments after the quoted "name".
        let err = analyze_source("\"help\" now").unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(err.span, SourceSpan::new(0, 6));
    }

    #[test]
    fn analysis_stops_at_the_first_error() {
        // First command resolves; the second, "foobar", is unknown and located.
        let semantic = analyze_source("help\nfoobar").unwrap_err();
        assert_eq!(semantic.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(semantic.span, SourceSpan::new(5, 11));
    }

    #[test]
    fn valid_commands_after_an_error_are_not_reported() {
        // "help" is fine, "foobar" fails first, so the later valid "echo" must
        // never appear: analysis is first-error, no recovery.
        let err = analyze_source("help\nfoobar\necho").unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::UnknownCommand);
        assert_eq!(err.span, SourceSpan::new(5, 11));
    }

    #[test]
    fn multi_command_program_span_covers_all_builtins() {
        let semantic = analyze_source("echo one\necho two").unwrap();
        assert_eq!(semantic.commands.len(), 2);
        assert_eq!(semantic.commands[0].span, SourceSpan::new(0, 8));
        assert_eq!(semantic.commands[1].span, SourceSpan::new(9, 17));
        assert_eq!(semantic.span, SourceSpan::new(0, 17));
    }

    #[test]
    fn builtin_span_is_independent_of_case() {
        // Span widths are byte-based on source text, unaffected by casing.
        let semantic = analyze_source("ClEaR   ").unwrap();
        assert_eq!(semantic.commands[0].span, SourceSpan::new(0, 5));
    }

    #[test]
    fn empty_command_is_an_error_kind() {
        // Unreachable through the parser, but Command is public; analyze must
        // report it honestly instead of inventing a name.
        let empty = Command { words: vec![], span: SourceSpan::new(3, 7) };
        let program = Program { commands: vec![empty], span: SourceSpan::new(3, 7) };
        let err = analyze(&program).unwrap_err();
        assert_eq!(err.kind, SemanticErrorKind::EmptyCommand);
        assert_eq!(err.span, SourceSpan::new(3, 7));
    }

    #[test]
    fn semantic_program_is_never_partial_on_error() {
        // Confirm analyze returns Err and not a partial Ok for the mixed case.
        let semantic = analyze_source("echo ok\nclear\nnope").unwrap_err();
        assert_eq!(semantic.kind, SemanticErrorKind::UnknownCommand);
    }

    // The parser never emits trailing/newline-only commands; confirm the whole
    // real pipeline of a leading-newline input only resolves real builtins.
    #[test]
    fn newlines_do_not_create_phantom_commands() {
        let semantic = analyze_source("\n\necho one\n").unwrap();
        assert_eq!(semantic.commands.len(), 1);
        assert_eq!(semantic.commands[0].kind, BuiltinCommandKind::Echo);
        // "\n\necho one\n": echo at [2,6), "one" at [7,10); the command span
        // runs from the first argument's start to the last argument's end.
        assert_eq!(semantic.commands[0].span, SourceSpan::new(2, 10));
    }
}
