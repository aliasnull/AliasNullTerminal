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
//! ```text
//! byte 0            kind (0 success, 1 lexer error, 2 parse error,
//!                          3 semantic error, 4 internal error)
//! byte 1            clear_requested (0/1; meaningful only on success)
//! u32               output unit count N (0 for errors)
//! N * (u32 len + UTF-8 bytes)   the output units, in order
//! [error kinds only]
//!   u32             message byte length
//!   message bytes   (UTF-8)
//!   u8              has_span (0/1)
//!   if has_span: u32 span_start, u32 span_end   (byte offsets, [start, end))
//! ```
//!
//! Every field is length-prefixed (never delimiter-split), so arbitrary echo
//! output -- including embedded newlines, spaces or non-ASCII text -- round
//! trips without ambiguity. The Kotlin mirror lives in
//! `AnShellCorePayloadCodec`.

use crate::execution::execute_builtin;
use crate::lexer::lex;
use crate::parser::parse;
use crate::semantic::analyze;
use crate::source::SourceText;

/// Result kinds carried in the payload byte 0. The numeric values are a fixed
/// cross-language contract shared with `AnShellCorePayloadCodec` on the Kotlin
/// side; they must not be reordered.
pub const KIND_SUCCESS: u8 = 0;
pub const KIND_LEXER_ERROR: u8 = 1;
pub const KIND_PARSE_ERROR: u8 = 2;
pub const KIND_SEMANTIC_ERROR: u8 = 3;
pub const KIND_INTERNAL_ERROR: u8 = 4;

/// The structured outcome of running one command string through the pipeline.
///
/// Pure data, deliberately free of Rust-specific error traits so the Kotlin
/// side never sees a Rust error type. Spans are byte offsets into the original
/// command text, half-open `[start, end)`, matching the crate's `SourceSpan`.
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
        /// Human-readable reason.
        message: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// The parser rejected the token stream.
    ParseError {
        /// Human-readable reason.
        message: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// Semantic analysis rejected the program (e.g. unknown command name).
    SemanticError {
        /// Human-readable reason.
        message: String,
        /// Byte offset span of the offending region.
        span_start: u32,
        span_end: u32,
    },
    /// A bridge-level failure that is not a language-pipeline error. Carries no
    /// source span.
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
            return AnShellCoreOutcome::LexerError {
                message: error.to_string(),
                span_start: to_u32(error.span.start),
                span_end: to_u32(error.span.end),
            };
        }
    };

    let program = match parse(&tokens) {
        Ok(program) => program,
        Err(error) => {
            return AnShellCoreOutcome::ParseError {
                message: error.to_string(),
                span_start: to_u32(error.span.start),
                span_end: to_u32(error.span.end),
            };
        }
    };

    let semantic = match analyze(&program) {
        Ok(semantic) => semantic,
        Err(error) => {
            return AnShellCoreOutcome::SemanticError {
                message: error.to_string(),
                span_start: to_u32(error.span.start),
                span_end: to_u32(error.span.end),
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

/// Serializes an outcome into the cross-language payload frame (see the module
/// documentation for the layout). Total and panic-free for any outcome.
pub fn encode_outcome(outcome: &AnShellCoreOutcome) -> Vec<u8> {
    let mut bytes = Vec::new();
    bytes.push(outcome.kind_byte());

    let (output, message, span): (&[String], Option<&str>, Option<(u32, u32)>) = match outcome {
        AnShellCoreOutcome::Success { output, clear_requested } => {
            bytes.push(if *clear_requested { 1 } else { 0 });
            (output.as_slice(), None, None)
        }
        AnShellCoreOutcome::LexerError { message, span_start, span_end }
        | AnShellCoreOutcome::ParseError { message, span_start, span_end }
        | AnShellCoreOutcome::SemanticError { message, span_start, span_end } => {
            bytes.push(0);
            (&[], Some(message.as_str()), Some((*span_start, *span_end)))
        }
        AnShellCoreOutcome::InternalError { message } => {
            bytes.push(0);
            (&[], Some(message.as_str()), None)
        }
    };

    push_u32(&mut bytes, output.len() as u32);
    for unit in output {
        push_string(&mut bytes, unit);
    }

    if let Some(message) = message {
        push_string(&mut bytes, message);
        match span {
            Some((start, end)) => {
                bytes.push(1);
                push_u32(&mut bytes, start);
                push_u32(&mut bytes, end);
            }
            None => bytes.push(0),
        }
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
        assert!(!clear_of("cLeAr"));
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
            AnShellCoreOutcome::SemanticError { message, span_start, span_end } => {
                assert!(message.contains("unknown command"), "{message}");
                assert_eq!(span_start, 0);
                assert_eq!(span_end, 6);
            }
            other => panic!("expected semantic error, got {other:?}"),
        }
    }

    #[test]
    fn unterminated_quote_is_a_structured_lexer_failure() {
        match run_command("\"oops") {
            AnShellCoreOutcome::LexerError { message, span_start, span_end } => {
                assert!(message.contains("unterminated"), "{message}");
                assert_eq!(span_start, 0);
                assert_eq!(span_end, 5);
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
    fn encode_error_layout_is_deterministic() {
        let bytes = encode_outcome(&AnShellCoreOutcome::SemanticError {
            message: "boom".to_owned(),
            span_start: 3,
            span_end: 9,
        });
        assert_eq!(bytes[0], KIND_SEMANTIC_ERROR);
        assert_eq!(bytes[1], 0);
        assert_eq!(read_u32(&bytes, 2), 0); // no output units
        let message_offset = 6;
        assert_eq!(read_u32(&bytes, message_offset), 4);
        assert_eq!(&bytes[10..14], b"boom");
        assert_eq!(bytes[14], 1); // has_span
        assert_eq!(read_u32(&bytes, 15), 3);
        assert_eq!(read_u32(&bytes, 19), 9);
        assert_eq!(bytes.len(), 23);
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
