//! The Part 27-G AN Shell bridge: run the real language pipeline for one input
//! string and encode the outcome into the cross-language result payload.
//!
//! This module owns the *pure* part of the Kotlin <-> Rust bridge. It has no
//! JNI and no `unsafe`: it drives the existing crate pipeline
//!
//! ```text
//! SourceText -> lex -> parse -> analyze -> execute_builtin
//! ```
//!
//! over one input string and returns a structured [`AnShellCoreOutcome`] (or the
//! failure that stopped the pipeline). The JNI layer (`crate::ffi`) converts a
//! command's UTF-8 bytes into a `&str`, calls [`run_command`], and hands the
//! encoded bytes to Kotlin; this module never lexes/parses/resolves again, so
//! the language pipeline has exactly one authority.
//!
//! Output contract
//! ---------------
//! [`encode_outcome`] serializes an [`AnShellCoreOutcome`] into the byte layout
//! that the Kotlin bridge decodes. The layout is a strict little-endian frame:
//!
//! Language errors (kinds 1..3) carry a *user-facing* message, a separate
//! *diagnostic* message, a stable error category code and an optional subject,
//! so Kotlin can render the user message verbatim and keep the detailed internal
//! text (with byte offsets) for diagnostics without parsing either string. The
//! internal error (kind 4) carries one honest message and no span. The frame:
//!
//! ```text
//! byte 0            kind (0 success, 1 lexer error, 2 parse error,
//!                          3 semantic error, 4 internal error)
//! byte 1            clear_requested (0/1; meaningful only on success)
//! u32               output unit count N (0 for errors)
//! N * (u32 len + UTF-8 bytes)   the output units, in order
//!
//! [language error kinds 1..3 only]
//!   u8              error category (see CATEGORY_* constants)
//!   u8              has_subject (0/1)
//!   if has_subject: u32 subject byte length + UTF-8 bytes
//!   u32             user_message byte length + UTF-8 bytes
//!   u32             diagnostic byte length + UTF-8 bytes
//!   u8              has_span (0/1)
//!   if has_span: u32 span_start, u32 span_end   (byte offsets, [start, end))
//!
//! [internal error kind 4 only]
//!   u32             message byte length + UTF-8 bytes
//!   u8              has_span (0/1)     (always 0)
//! ```
//!
//! `user_message` is the concise, stable wording a shell user should see (no
//! brand prefix and no byte offsets); `diagnostic` is the detailed internal text
//! (e.g. "…at byte 5") preserved for logs and future debug tooling. Neither is
//! ever derived by parsing the other.
//!
//! Every field is length-prefixed (never delimiter-split), so arbitrary echo
//! output -- including embedded newlines, spaces or non-ASCII text -- round
//! trips without ambiguity. The Kotlin mirror lives in
//! `AnShellCorePayloadCodec`.

use crate::execution::execute_builtin;
use crate::lexer::{lex, LexerErrorKind};
use crate::parser::{parse, ParseErrorKind};
use crate::semantic::{analyze, SemanticErrorKind};
use crate::source::{SourceSpan, SourceText};

/// Result kinds carried in the payload byte 0. The numeric values are a fixed
/// cross-language contract shared with `AnShellCorePayloadCodec` on the Kotlin
/// side; they must not be reordered.
pub const KIND_SUCCESS: u8 = 0;
pub const KIND_LEXER_ERROR: u8 = 1;
pub const KIND_PARSE_ERROR: u8 = 2;
pub const KIND_SEMANTIC_ERROR: u8 = 3;
pub const KIND_INTERNAL_ERROR: u8 = 4;

/// Stable category codes carried on language errors (kinds 1..3). A category
/// identifies the exact failing rule so Kotlin can distinguish cases (e.g. an
/// unknown command, which also gets the help hint) without parsing message text.
/// These values are a cross-language contract shared with the Kotlin decoder;
/// they must not be reordered or renumbered.
pub const CATEGORY_INTERNAL: u8 = 0;
pub const CATEGORY_LEXER_UNTERMINATED_STRING: u8 = 1;
pub const CATEGORY_PARSE_MISSING_EOF: u8 = 2;
pub const CATEGORY_PARSE_TOKEN_AFTER_EOF: u8 = 3;
pub const CATEGORY_SEMANTIC_UNKNOWN_COMMAND: u8 = 4;
pub const CATEGORY_SEMANTIC_EMPTY_COMMAND: u8 = 5;

/// Stable, concise user-facing wording for language errors (no brand prefix, no
/// byte offsets). The single authority for this copy lives here, in the Rust
/// bridge, so Kotlin renders it verbatim instead of inventing language messages.
const USER_UNTERMINATED_STRING: &str = "Unterminated double quote.";
const USER_PARSE_ERROR: &str = "The command could not be parsed.";
const USER_EMPTY_COMMAND: &str = "The command is empty.";

/// The structured outcome of running one command string through the pipeline.
///
/// Pure data, deliberately free of Rust-specific error traits so the Kotlin
/// side never sees a Rust error type. Spans are byte offsets into the original
/// command text, half-open `[start, end)`, matching the crate's `SourceSpan`.
///
/// Every language error separates what the user should see (concise stable
/// wording kept in `user_message`) from what a log or future debug tool should
/// see (`diagnostic`, which may carry byte offsets), and both ride alongside the
/// stable category code and an optional `subject`
/// (e.g. the unknown command name). Kotlin renders `user_message` verbatim and
/// never parses `diagnostic`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AnShellCoreOutcome {
    /// Every command executed. `output` is the concatenated output units of the
    /// executed commands in source order; `clear_requested` is true when any
    /// command requested a terminal clear.
    Success {
        /// Emitted output units, in order. May be empty.
        output: Vec<String>,
        /// True when the executed command(s) requested a terminal clear.
        clear_requested: bool,
    },
    /// The lexer rejected the input before parsing (e.g. unterminated string).
    LexerError {
        /// Stable category code identifying the exact lexical rule that failed.
        category: u8,
        /// Concise stable wording for a shell user (no byte offsets).
        user_message: String,
        /// Detailed internal diagnostic text, retained for logs/debug.
        diagnostic: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// The parser rejected the token stream.
    ParseError {
        /// Stable category code identifying the exact syntax rule that failed.
        category: u8,
        /// Concise stable wording for a shell user (no byte offsets).
        user_message: String,
        /// Detailed internal diagnostic text, retained for logs/debug.
        diagnostic: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// Semantic analysis rejected the program (e.g. unknown command name).
    SemanticError {
        /// Stable category code identifying the exact semantic rule that failed.
        category: u8,
        /// The offending name/value exactly as the user typed it, when the rule
        /// involves one (the unknown command name). Absent otherwise.
        subject: Option<String>,
        /// Concise stable wording for a shell user (no byte offsets).
        user_message: String,
        /// Detailed internal diagnostic text, retained for logs/debug.
        diagnostic: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// A bridge-level failure that is not a language-pipeline error. Carries no
    /// source span and one honest message (no user/diagnostic split).
    InternalError {
        /// Human-readable reason.
        message: String,
    },
}

impl AnShellCoreOutcome {
    /// The payload `kind` byte for this outcome.
    pub fn kind_byte(&self) -> u8 {
        match self {
            AnShellCoreOutcome::Success { .. } => KIND_SUCCESS,
            AnShellCoreOutcome::LexerError { .. } => KIND_LEXER_ERROR,
            AnShellCoreOutcome::ParseError { .. } => KIND_PARSE_ERROR,
            AnShellCoreOutcome::SemanticError { .. } => KIND_SEMANTIC_ERROR,
            AnShellCoreOutcome::InternalError { .. } => KIND_INTERNAL_ERROR,
        }
    }
}

/// Runs one command string through the full existing pipeline and returns the
/// outcome.
///
/// * Empty and whitespace-only input lex/parse/analyze to an empty program, so
///   they return `Success { output: [], clear_requested: false }` -- never an
///   error and never a crash.
/// * The pipeline fails fast: the first lexer/parser/semantic error stops the
///   run and is returned as the matching outcome.
/// * On success every recognised built-in is executed in source order and its
///   output units are appended; `clear_requested` is the OR across commands.
///
/// This function is total and panic-free for any `&str` input.
pub fn run_command(source: &str) -> AnShellCoreOutcome {
    let text = SourceText::new(source);

    let tokens = match lex(&text) {
        Ok(tokens) => tokens,
        Err(error) => {
            let (category, user_message) = match error.kind {
                LexerErrorKind::UnterminatedString => (
                    CATEGORY_LEXER_UNTERMINATED_STRING,
                    USER_UNTERMINATED_STRING.to_owned(),
                ),
            };
            return AnShellCoreOutcome::LexerError {
                category,
                user_message,
                diagnostic: error.to_string(),
                span_start: to_u32(error.span.start),
                span_end: to_u32(error.span.end),
            };
        }
    };

    let program = match parse(&tokens) {
        Ok(program) => program,
        Err(error) => {
            let (category, user_message) = match error.kind {
                ParseErrorKind::MissingEof => {
                    (CATEGORY_PARSE_MISSING_EOF, USER_PARSE_ERROR.to_owned())
                }
                ParseErrorKind::TokenAfterEof => {
                    (CATEGORY_PARSE_TOKEN_AFTER_EOF, USER_PARSE_ERROR.to_owned())
                }
            };
            return AnShellCoreOutcome::ParseError {
                category,
                user_message,
                diagnostic: error.to_string(),
                span_start: to_u32(error.span.start),
                span_end: to_u32(error.span.end),
            };
        }
    };

    let semantic = match analyze(&program) {
        Ok(semantic) => semantic,
        Err(error) => {
            return match error.kind {
                // The unknown command name is the source slice of the failing
                // name token: exactly what the user typed (quotes included for a
                // quoted "name"), sliced by the language core itself -- Kotlin
                // never re-derives it.
                SemanticErrorKind::UnknownCommand => {
                    let subject = text.slice(error.span).to_string();
                    AnShellCoreOutcome::SemanticError {
                        category: CATEGORY_SEMANTIC_UNKNOWN_COMMAND,
                        subject: Some(subject.clone()),
                        user_message: format!("command not found: {subject}"),
                        diagnostic: error.to_string(),
                        span_start: to_u32(error.span.start),
                        span_end: to_u32(error.span.end),
                    }
                }
                SemanticErrorKind::EmptyCommand => AnShellCoreOutcome::SemanticError {
                    category: CATEGORY_SEMANTIC_EMPTY_COMMAND,
                    subject: None,
                    user_message: USER_EMPTY_COMMAND.to_owned(),
                    diagnostic: error.to_string(),
                    span_start: to_u32(error.span.start),
                    span_end: to_u32(error.span.end),
                },
            };
        }
    };

    let mut output = Vec::new();
    let mut clear_requested = false;
    for command in &semantic.commands {
        let result = execute_builtin(command);
        output.extend(result.output);
        clear_requested |= result.clear_requested;
    }

    AnShellCoreOutcome::Success { output, clear_requested }
}

/// How an error outcome is encoded (see the module-documentation layout).
/// Private: only `encode_outcome` needs to distinguish the two error shapes.
enum ErrorSection<'a> {
    /// A language error (kinds 1..3): category, optional subject, the concise
    /// user-facing message, the detailed diagnostic and an optional span.
    Language {
        category: u8,
        subject: Option<&'a str>,
        user_message: &'a str,
        diagnostic: &'a str,
        span: Option<(u32, u32)>,
    },
    /// An internal error (kind 4): one honest message, never a span.
    Internal { message: &'a str },
}

/// Serializes an outcome into the cross-language payload frame (see the module
/// documentation for the layout). Total and panic-free for any outcome.
pub fn encode_outcome(outcome: &AnShellCoreOutcome) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.push(outcome.kind_byte());

    let (output, error_section): (&[String], Option<ErrorSection<'_>>) = match outcome {
        AnShellCoreOutcome::Success { output, clear_requested } => {
            bytes.push(if *clear_requested { 1 } else { 0 });
            (output.as_slice(), None)
        }
        AnShellCoreOutcome::LexerError {
            category,
            user_message,
            diagnostic,
            span_start,
            span_end,
        }
        | AnShellCoreOutcome::ParseError {
            category,
            user_message,
            diagnostic,
            span_start,
            span_end,
        } => {
            bytes.push(0);
            (
                &[],
                Some(ErrorSection::Language {
                    category: *category,
                    subject: None,
                    user_message: user_message.as_str(),
                    diagnostic: diagnostic.as_str(),
                    span: Some((*span_start, *span_end)),
                }),
            )
        }
        AnShellCoreOutcome::SemanticError {
            category,
            subject,
            user_message,
            diagnostic,
            span_start,
            span_end,
        } => {
            bytes.push(0);
            (
                &[],
                Some(ErrorSection::Language {
                    category: *category,
                    subject: subject.as_deref(),
                    user_message: user_message.as_str(),
                    diagnostic: diagnostic.as_str(),
                    span: Some((*span_start, *span_end)),
                }),
            )
        }
        AnShellCoreOutcome::InternalError { message } => {
            bytes.push(0);
            (&[], Some(ErrorSection::Internal { message: message.as_str() }))
        }
    };

    push_u32(&mut bytes, output.len() as u32);
    for unit in output {
        push_string(&mut bytes, unit);
    }

    match error_section {
        Some(ErrorSection::Language { category, subject, user_message, diagnostic, span }) => {
            bytes.push(category);
            match subject {
                Some(subject) => {
                    bytes.push(1);
                    push_string(&mut bytes, subject);
                }
                None => bytes.push(0),
            }
            push_string(&mut bytes, user_message);
            push_string(&mut bytes, diagnostic);
            match span {
                Some((start, end)) => {
                    bytes.push(1);
                    push_u32(&mut bytes, start);
                    push_u32(&mut bytes, end);
                }
                None => bytes.push(0),
            }
        }
        Some(ErrorSection::Internal { message }) => {
            push_string(&mut bytes, message);
            // Internal errors never carry a span.
            bytes.push(0);
        }
        None => {}
    }

    bytes
}

/// Saturating byte-length conversion from `usize` to the payload's `u32` fields.
/// Source text reaching Rust through the JNI byte-array path is bounded by a
/// JNI `jsize`, so real inputs never exceed `u32`, but the encoder stays total.
fn to_u32(value: usize) -> u32 {
    value.min(u32::MAX as usize) as u32
}

fn push_u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend_from_slice(&value.to_le_bytes());
}

fn push_string(bytes: &mut Vec<u8>, value: &str) {
    push_u32(bytes, value.len() as u32);
    bytes.extend_from_slice(value.as_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn success(source: &str) -> AnShellCoreOutcome {
        let outcome = run_command(source);
        assert!(matches!(outcome, AnShellCoreOutcome::Success { .. }), "for {source:?}: {outcome:?}");
        outcome
    }

    fn output_of(source: &str) -> Vec<String> {
        match success(source) {
            AnShellCoreOutcome::Success { output, .. } => output,
            _ => unreachable!(),
        }
    }

    fn clear_of(source: &str) -> bool {
        match success(source) {
            AnShellCoreOutcome::Success { clear_requested, .. } => clear_requested,
            _ => unreachable!(),
        }
    }

    /// Reads a little-endian u32 at `offset` inside an encoded payload.
    fn read_u32(bytes: &[u8], offset: usize) -> u32 {
        u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
    }

    #[test]
    fn api_version_constant_is_unchanged_and_correct() {
        // The identity function is preserved byte-for-byte from Part 27-B; this
        // guards the value the Kotlin handshake compares against.
        assert_eq!(crate::aliasnull_an_shell_core_api_version(), 0x0000_0100);
    }

    #[test]
    fn help_succeeds_through_the_shared_pipeline() {
        let output = output_of("help");
        assert_eq!(output.len(), 1);
        assert!(output[0].starts_with("AliasNull Shell - temporary frontend commands\n"));
        assert!(output[0].contains("Available commands:\n"));
        assert!(!clear_of("help"));
    }

    #[test]
    fn about_succeeds_through_the_shared_pipeline() {
        let output = output_of("about");
        assert_eq!(output.len(), 1);
        assert!(output[0].starts_with("AliasNull\n"));
        assert!(!clear_of("about"));
    }

    #[test]
    fn echo_returns_its_arguments() {
        assert_eq!(output_of("echo hello world"), vec!["hello world".to_owned()]);
        assert_eq!(output_of("echo hello"), vec!["hello".to_owned()]);
    }

    #[test]
    fn echo_preserves_utf8_command_input() {
        assert_eq!(output_of("echo \"दुनिया\""), vec!["दुनिया".to_owned()]);
    }

    #[test]
    fn echo_quoted_argument_uses_interpreted_value() {
        assert_eq!(output_of("echo \"hello world\""), vec!["hello world".to_owned()]);
    }

    #[test]
    fn semantic_recognition_is_case_insensitive() {
        assert_eq!(output_of("HELP").len(), 1);
        assert_eq!(output_of("ECHO hello"), vec!["hello".to_owned()]);
        assert_eq!(output_of("About").len(), 1);
        assert!(clear_of("cLeAr"));
    }

    #[test]
    fn clear_preserves_clear_requested() {
        assert!(output_of("clear").is_empty());
        assert!(clear_of("clear"));
    }

    #[test]
    fn empty_and_whitespace_input_is_a_successful_noop() {
        for source in ["", "   ", "\n", " \t \n "] {
            match run_command(source) {
                AnShellCoreOutcome::Success { output, clear_requested } => {
                    assert!(output.is_empty(), "for {source:?}");
                    assert!(!clear_requested, "for {source:?}");
                }
                other => panic!("expected success for {source:?}, got {other:?}"),
            }
        }
    }

    #[test]
    fn unknown_command_is_a_structured_semantic_failure() {
        match run_command("foobar") {
            AnShellCoreOutcome::SemanticError {
                category,
                subject,
                user_message,
                diagnostic,
                span_start,
                span_end,
            } => {
                assert_eq!(category, CATEGORY_SEMANTIC_UNKNOWN_COMMAND);
                assert_eq!(subject.as_deref(), Some("foobar"));
                assert_eq!(user_message, "command not found: foobar");
                assert!(diagnostic.contains("unknown command"), "{diagnostic}");
                assert_eq!(span_start, 0);
                assert_eq!(span_end, 6);
            }
            other => panic!("expected semantic error, got {other:?}"),
        }
    }

    #[test]
    fn unterminated_quote_is_a_structured_lexer_failure() {
        match run_command("\"oops") {
            AnShellCoreOutcome::LexerError {
                category,
                user_message,
                diagnostic,
                span_start,
                span_end,
            } => {
                assert_eq!(category, CATEGORY_LEXER_UNTERMINATED_STRING);
                assert_eq!(user_message, "Unterminated double quote.");
                assert!(diagnostic.contains("unterminated"), "{diagnostic}");
                assert_eq!(span_start, 0);
                assert_eq!(span_end, 5);
            }
            other => panic!("expected lexer error, got {other:?}"),
        }
    }

    #[test]
    fn unknown_command_carries_its_typed_name_structurally() {
        // The subject is the name exactly as the user typed it, sliced from the
        // source by the language core; Kotlin never re-derives it.
        for (source, expected) in [
            ("hi", "hi"),
            ("foobar", "foobar"),
            ("ECHO2", "ECHO2"),
        ] {
            match run_command(source) {
                AnShellCoreOutcome::SemanticError { subject, user_message, .. } => {
                    assert_eq!(subject.as_deref(), Some(expected), "for {source:?}");
                    assert_eq!(
                        user_message,
                        format!("command not found: {expected}"),
                        "for {source:?}"
                    );
                }
                other => panic!("expected semantic error for {source:?}, got {other:?}"),
            }
        }
        // A quoted "name" keeps its quotes: that is the raw text the user typed,
        // and the reference executor reports the quoted name verbatim too.
        match run_command("\"echo\" hi") {
            AnShellCoreOutcome::SemanticError { subject, .. } => {
                assert_eq!(subject.as_deref(), Some("\"echo\""));
            }
            other => panic!("expected semantic error, got {other:?}"),
        }
    }

    #[test]
    fn unterminated_quote_user_message_is_stable_and_diagnostic_stays_rich() {
        // The user-facing wording is concise and carries no byte offsets, while
        // the detailed diagnostic (which locates the quote by byte) is preserved
        // separately - the two never depend on parsing one another.
        match run_command("echo \"hi") {
            AnShellCoreOutcome::LexerError {
                category,
                user_message,
                diagnostic,
                span_start,
                span_end,
            } => {
                assert_eq!(category, CATEGORY_LEXER_UNTERMINATED_STRING);
                assert_eq!(user_message, "Unterminated double quote.");
                assert!(!user_message.contains("byte"), "{user_message}");
                assert!(diagnostic.contains("byte 5"), "{diagnostic}");
                assert_eq!(span_start, 5);
                assert_eq!(span_end, 8);
            }
            other => panic!("expected lexer error, got {other:?}"),
        }
    }

    #[test]
    fn multi_command_input_executes_all_commands_in_order() {
        let output = output_of("echo one\necho two");
        assert_eq!(output, vec!["one".to_owned(), "two".to_owned()]);
        // clear does not leak into a later command's output.
        let output = output_of("clear\necho hi");
        assert_eq!(output, vec!["hi".to_owned()]);
        assert!(clear_of("clear\necho hi"));
    }

    #[test]
    fn encode_success_layout_is_deterministic() {
        let outcome = AnShellCoreOutcome::Success {
            output: vec!["ab".to_owned(), "".to_owned()],
            clear_requested: true,
        };
        let bytes = encode_outcome(&outcome);
        assert_eq!(bytes[0], KIND_SUCCESS);
        assert_eq!(bytes[1], 1);
        assert_eq!(read_u32(&bytes, 2), 2);
        // unit 1: len 2, "ab"
        assert_eq!(read_u32(&bytes, 6), 2);
        assert_eq!(&bytes[10..12], b"ab");
        // unit 2: len 0
        assert_eq!(read_u32(&bytes, 12), 0);
        // no error section for success
        assert_eq!(bytes.len(), 16);
    }

    #[test]
    fn encode_semantic_error_layout_is_deterministic() {
        // A semantic unknown-command error carries category, subject, the concise
        // user message, the rich diagnostic and the span. Offsets here mirror the
        // module-documentation frame and must stay in lock-step with the Kotlin
        // decoder.
        let bytes = encode_outcome(&AnShellCoreOutcome::SemanticError {
            category: CATEGORY_SEMANTIC_UNKNOWN_COMMAND,
            subject: Some("hi".to_owned()),
            user_message: "command not found: hi".to_owned(),
            diagnostic: "boom".to_owned(),
            span_start: 3,
            span_end: 9,
        });
        assert_eq!(bytes[0], KIND_SEMANTIC_ERROR);
        assert_eq!(bytes[1], 0); // clear_requested
        assert_eq!(read_u32(&bytes, 2), 0); // no output units
        assert_eq!(bytes[6], CATEGORY_SEMANTIC_UNKNOWN_COMMAND);
        assert_eq!(bytes[7], 1); // has_subject
        assert_eq!(read_u32(&bytes, 8), 2); // subject length
        assert_eq!(&bytes[12..14], b"hi"); // subject
        assert_eq!(read_u32(&bytes, 14), 21); // user_message length
        assert_eq!(&bytes[18..39], b"command not found: hi");
        assert_eq!(read_u32(&bytes, 39), 4); // diagnostic length
        assert_eq!(&bytes[43..47], b"boom"); // diagnostic
        assert_eq!(bytes[47], 1); // has_span
        assert_eq!(read_u32(&bytes, 48), 3); // span_start
        assert_eq!(read_u32(&bytes, 52), 9); // span_end
        assert_eq!(bytes.len(), 56);
    }

    #[test]
    fn encode_lexer_error_has_category_and_no_subject() {
        // A lexer error carries its category and user message but never a subject
        // (has_subject == 0). With the subject byte skipped, the user message
        // length prefix sits at offset 8.
        let bytes = encode_outcome(&AnShellCoreOutcome::LexerError {
            category: CATEGORY_LEXER_UNTERMINATED_STRING,
            user_message: "Unterminated double quote.".to_owned(),
            diagnostic: "boom".to_owned(),
            span_start: 0,
            span_end: 5,
        });
        assert_eq!(bytes[0], KIND_LEXER_ERROR);
        assert_eq!(bytes[6], CATEGORY_LEXER_UNTERMINATED_STRING);
        assert_eq!(bytes[7], 0); // has_subject
        assert_eq!(read_u32(&bytes, 8), 26); // "Unterminated double quote."
        assert_eq!(&bytes[12..38], b"Unterminated double quote.");
    }

    #[test]
    fn encode_internal_error_has_no_span() {
        let bytes = encode_outcome(&AnShellCoreOutcome::InternalError {
            message: "bad utf8".to_owned(),
        });
        assert_eq!(bytes[0], KIND_INTERNAL_ERROR);
        assert_eq!(bytes[1], 0);
        assert_eq!(read_u32(&bytes, 2), 0);
        assert_eq!(read_u32(&bytes, 6), 8); // "bad utf8"
        // Message is 8 bytes at offsets 10..17, so the has_span flag is byte 18.
        assert_eq!(bytes[18], 0); // has_span == 0
        assert_eq!(bytes.len(), 19);
    }

    #[test]
    fn run_and_encode_do_not_panic_on_adversarial_input() {
        // Long inputs are built as owned Strings first so the slice below holds
        // only &str (no temporary-borrow or mixed-type array issues).
        let long_x = "x".repeat(4096);
        let long_echo = format!("echo {}", "a".repeat(5000));
        let adversarial = [
            "\"", "\\", "\"\\\"", "echo \"", "echo \"a\nb\"", "\0echo", "echo a|b;c>d&",
            "echo $HOME", "echo *", "\t\t", "echo 'single quotes'", "echo one  two",
            "HELP\necho\n\nclear", "echo दुनिया", "中文测试 echo", "echo \"emoji 😀\"",
            long_x.as_str(),
            long_echo.as_str(),
        ];
        for source in adversarial {
            let outcome = run_command(source);
            let bytes = encode_outcome(&outcome);
            assert!(!bytes.is_empty(), "encode returned empty payload for {source:?}");
            assert_eq!(bytes[0], outcome.kind_byte());
        }
    }

    #[test]
    fn internal_error_encodes_without_span_and_round_trips_kind() {
        let outcome = AnShellCoreOutcome::InternalError {
            message: "internal".to_owned(),
        };
        let bytes = encode_outcome(&outcome);
        assert_eq!(bytes[0], outcome.kind_byte());
    }
}
