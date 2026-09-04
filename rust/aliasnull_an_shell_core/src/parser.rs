//! The Part 27-D AN Shell parser: token stream -> minimal AST.
//!
//! The parser is a small deterministic cursor over a token slice. It owns token
//! ordering, command boundaries, AST construction and syntactic token-stream
//! validation. It never re-scans source text and never re-parses quotes: the
//! lexer remains the sole lexical authority, and this module reads only the
//! tokens `lex` produced.

use crate::ast::{Argument, Command, Program};
use crate::source::SourceSpan;
use crate::token::{Token, TokenKind};

/// The kind of a parsing failure.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ParseErrorKind {
    /// The token stream ended without the single required trailing `Eof`
    /// token. Also returned for an empty token slice.
    MissingEof,
    /// A token (possibly a second `Eof`) follows the terminal `Eof` token.
    /// Per the EOF policy, `Eof` terminates the stream and no token may follow
    /// it.
    TokenAfterEof,
}

/// A parsing failure with enough source information to locate it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ParseError {
    /// Which syntactic rule failed.
    pub kind: ParseErrorKind,
    /// The offending region. For `MissingEof` it is the last token in the
    /// stream (or `[0, 0)` for an empty stream); for `TokenAfterEof` it is the
    /// first token after the terminal `Eof`.
    pub span: SourceSpan,
}

impl ParseError {
    /// Creates a parsing error.
    pub const fn new(kind: ParseErrorKind, span: SourceSpan) -> Self {
        ParseError { kind, span }
    }
}

impl core::fmt::Display for ParseError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self.kind {
            ParseErrorKind::MissingEof => write!(f, "missing terminal EOF token"),
            ParseErrorKind::TokenAfterEof => {
                write!(f, "unexpected token after terminal EOF token")
            }
        }
    }
}

impl std::error::Error for ParseError {}

/// Parses a token stream into a minimal AN Shell `Program`.
///
/// Grammar (deliberately minimal, not a complete shell grammar):
///
/// ```text
/// program          := separator* command_sequence? EOF
/// command_sequence := command (separator+ command)*
/// separator        := Newline
/// command          := argument+
/// argument         := Word | Str
/// ```
///
/// Parser ownership
/// ----------------
/// The parser decides token ordering, command boundaries and AST shape. It
/// does not interpret words, so a `Word` such as `a|b` or `$HOME` (the lexer
/// kept the metacharacters as ordinary word content) stays a single `Word`
/// argument here; no shell syntax is invented.
///
/// EOF policy
/// ----------
/// The parser validates the stream instead of assuming lexer well-formedness:
///
/// * an empty token slice is an error (`MissingEof`);
/// * a stream without a trailing `Eof` is an error (`MissingEof`);
/// * any token after the terminal `Eof` -- including a second `Eof` -- is an
///   error (`TokenAfterEof`).
///
/// With the current closed `TokenKind` vocabulary every token has a defined
/// grammar position, so no further "unexpected token" case is reachable; the
/// exhaustive match over `TokenKind` means a future token kind fails to
/// compile until the grammar explicitly handles it, rather than being silently
/// mis-parsed.
///
/// Spans
/// -----
/// All spans are the existing half-open `[start, end)` byte offsets. A
/// `Command` span runs from its first argument's start to its last argument's
/// end, excluding separator newlines. A `Program` span is `[0, 0)` for an
/// empty program, otherwise the first command's start through the last
/// command's end.
pub fn parse(tokens: &[Token]) -> Result<Program, ParseError> {
    let mut parser = Parser { tokens, pos: 0 };
    let mut commands = Vec::new();

    loop {
        parser.skip_separators();

        match parser.peek() {
            // Ran out of tokens before any Eof: the stream is unterminated.
            None => {
                let span = tokens
                    .last()
                    .map(|t| t.span)
                    .unwrap_or(SourceSpan::new(0, 0));
                return Err(ParseError::new(ParseErrorKind::MissingEof, span));
            }
            Some(token) if token.kind == TokenKind::Eof => {
                // Eof is terminal only when nothing follows it.
                if parser.pos != tokens.len() - 1 {
                    let next = &tokens[parser.pos + 1];
                    return Err(ParseError::new(ParseErrorKind::TokenAfterEof, next.span));
                }
                break;
            }
            // A Word or Str begins a command.
            Some(_) => commands.push(parser.parse_command()?),
        }
    }

    let span = commands
        .first()
        .zip(commands.last())
        .map_or(SourceSpan::new(0, 0), |(first, last)| {
            SourceSpan::new(first.span.start, last.span.end)
        });

    Ok(Program { commands, span })
}

/// A deterministic cursor over the token slice. Private: nothing outside this
/// module needs it.
struct Parser<'a> {
    tokens: &'a [Token],
    pos: usize,
}

impl<'a> Parser<'a> {
    fn peek(&self) -> Option<&Token> {
        self.tokens.get(self.pos)
    }

    /// Advances over consecutive separator newlines.
    fn skip_separators(&mut self) {
        while self.peek().is_some_and(|t| t.kind == TokenKind::Newline) {
            self.pos += 1;
        }
    }

    /// Parses one command (`argument+`), stopping at a separator, the terminal
    /// `Eof` or the end of the slice without consuming the stopping token.
    fn parse_command(&mut self) -> Result<Command, ParseError> {
        // The caller only invokes this when the current token is Word or Str,
        // so `words` is never empty.
        let start = self.tokens[self.pos].span.start;
        let mut words = Vec::new();

        while self.pos < self.tokens.len() {
            let token = &self.tokens[self.pos];
            match &token.kind {
                TokenKind::Word(value) => {
                    words.push(Argument::Word { value: value.clone(), span: token.span });
                }
                TokenKind::Str(value) => {
                    words.push(Argument::String { value: value.clone(), span: token.span });
                }
                TokenKind::Newline | TokenKind::Eof => break,
            }
            self.pos += 1;
        }

        let end = words.last().map_or(start, |w| w.span().end);
        Ok(Command { words, span: SourceSpan::new(start, end) })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::lexer::lex;
    use crate::source::SourceText;

    /// Runs the real `lex` -> `parse` pipeline over `source`.
    fn parse_source(source: &str) -> Program {
        let tokens = lex(&SourceText::new(source)).expect("lexer accepted this input");
        parse(&tokens).expect("parser accepted this input")
    }

    fn tok(kind: TokenKind, span: SourceSpan) -> Token {
        Token::new(kind, span)
    }

    fn word_token(value: &str, span: SourceSpan) -> Token {
        tok(TokenKind::Word(value.to_string()), span)
    }

    fn eof_token(span: SourceSpan) -> Token {
        tok(TokenKind::Eof, span)
    }

    #[test]
    fn empty_input_produces_empty_program() {
        let program = parse_source("");
        assert!(program.commands.is_empty());
        assert_eq!(program.span, SourceSpan::new(0, 0));
    }

    #[test]
    fn whitespace_only_produces_empty_program() {
        for source in ["   ", " \t\r ", "\n", " \n \n"] {
            let program = parse_source(source);
            assert!(program.commands.is_empty(), "expected no commands for {source:?}");
            assert_eq!(program.span, SourceSpan::new(0, 0));
        }
    }

    #[test]
    fn single_word_produces_one_command() {
        let program = parse_source("echo");
        assert_eq!(program.commands.len(), 1);
        let command = &program.commands[0];
        assert_eq!(command.words.len(), 1);
        assert_eq!(command.words[0].value(), "echo");
        assert!(matches!(&command.words[0], Argument::Word { .. }));
        assert_eq!(command.span, SourceSpan::new(0, 4));
        assert_eq!(program.span, SourceSpan::new(0, 4));
    }

    #[test]
    fn multiple_words_become_command_arguments() {
        let program = parse_source("echo hello world");
        assert_eq!(program.commands.len(), 1);
        let words = &program.commands[0].words;
        assert_eq!(words.len(), 3);
        assert_eq!(words[0].value(), "echo");
        assert_eq!(words[1].value(), "hello");
        assert_eq!(words[2].value(), "world");
        assert!(words.iter().all(|w| matches!(w, Argument::Word { .. })));
    }

    #[test]
    fn quoted_string_remains_distinct_argument_kind() {
        let program = parse_source("echo \"hello world\"");
        assert_eq!(program.commands.len(), 1);
        let words = &program.commands[0].words;
        assert_eq!(words.len(), 2);

        assert!(matches!(&words[0], Argument::Word { .. }));
        assert_eq!(words[0].value(), "echo");

        assert!(matches!(&words[1], Argument::String { .. }));
        // The lexer value is used as-is: quotes are already removed.
        assert_eq!(words[1].value(), "hello world");
        // The string span still includes the surrounding quotes. The token
        // starts at byte 5 (the opening quote) and ends past byte 17 (the
        // closing quote), i.e. [5, 18).
        assert_eq!(words[1].span(), SourceSpan::new(5, 18));
    }

    #[test]
    fn multiple_commands_split_by_newline() {
        let program = parse_source("echo one\necho two");
        assert_eq!(program.commands.len(), 2);
        assert_eq!(program.commands[0].words.len(), 2);
        assert_eq!(program.commands[0].words[0].value(), "echo");
        assert_eq!(program.commands[0].words[1].value(), "one");
        assert_eq!(program.commands[1].words[0].value(), "echo");
        assert_eq!(program.commands[1].words[1].value(), "two");
        assert_eq!(program.commands[0].span, SourceSpan::new(0, 8));
        assert_eq!(program.commands[1].span, SourceSpan::new(9, 17));
        assert_eq!(program.span, SourceSpan::new(0, 17));
    }

    #[test]
    fn consecutive_newlines_do_not_create_empty_commands() {
        let program = parse_source("echo one\n\necho two");
        assert_eq!(program.commands.len(), 2);
        assert_eq!(program.commands[0].words.len(), 2);
        assert_eq!(program.commands[1].words.len(), 2);
    }

    #[test]
    fn leading_newlines_do_not_create_empty_commands() {
        let program = parse_source("\n\n echo");
        assert_eq!(program.commands.len(), 1);
        assert_eq!(program.commands[0].words.len(), 1);
        assert_eq!(program.commands[0].words[0].value(), "echo");
        assert_eq!(program.commands[0].span, SourceSpan::new(3, 7));
        assert_eq!(program.span, SourceSpan::new(3, 7));
    }

    #[test]
    fn trailing_newlines_do_not_create_empty_commands() {
        let program = parse_source("echo\n\n");
        assert_eq!(program.commands.len(), 1);
        assert_eq!(program.commands[0].words.len(), 1);
        assert_eq!(program.commands[0].words[0].value(), "echo");
        assert_eq!(program.commands[0].span, SourceSpan::new(0, 4));
        assert_eq!(program.span, SourceSpan::new(0, 4));
    }

    #[test]
    fn command_span_covers_arguments_only() {
        let program = parse_source("echo hello\n");
        assert_eq!(program.commands.len(), 1);
        let command = &program.commands[0];
        // "echo hello" occupies bytes 0..10; the newline at byte 10 is excluded.
        assert_eq!(command.span, SourceSpan::new(0, 10));
        assert_eq!(program.span, SourceSpan::new(0, 10));
    }

    #[test]
    fn argument_spans_match_token_spans() {
        let source = "cat /p\n";
        let tokens = lex(&SourceText::new(source)).unwrap();
        let program = parse(&tokens).unwrap();

        let arg_spans: Vec<SourceSpan> =
            program.commands[0].words.iter().map(|w| w.span()).collect();
        let word_token_spans: Vec<SourceSpan> = tokens
            .iter()
            .filter(|t| matches!(&t.kind, TokenKind::Word(_)))
            .map(|t| t.span)
            .collect();
        assert_eq!(arg_spans, word_token_spans);
        assert_eq!(arg_spans, vec![SourceSpan::new(0, 3), SourceSpan::new(4, 6)]);
    }

    #[test]
    fn missing_eof_is_parse_error() {
        // A hand-built stream with no trailing Eof token.
        let tokens = vec![word_token("echo", SourceSpan::new(0, 4))];
        let err = parse(&tokens).unwrap_err();
        assert_eq!(err.kind, ParseErrorKind::MissingEof);
        assert_eq!(err.span, SourceSpan::new(0, 4));
    }

    #[test]
    fn tokens_after_eof_are_parse_error() {
        let tokens = vec![
            word_token("echo", SourceSpan::new(0, 4)),
            eof_token(SourceSpan::new(4, 4)),
            word_token("x", SourceSpan::new(5, 6)),
        ];
        let err = parse(&tokens).unwrap_err();
        assert_eq!(err.kind, ParseErrorKind::TokenAfterEof);
        assert_eq!(err.span, SourceSpan::new(5, 6));
    }

    #[test]
    fn duplicate_eof_is_parse_error() {
        let tokens = vec![
            word_token("echo", SourceSpan::new(0, 4)),
            eof_token(SourceSpan::new(4, 4)),
            eof_token(SourceSpan::new(4, 4)),
        ];
        let err = parse(&tokens).unwrap_err();
        assert_eq!(err.kind, ParseErrorKind::TokenAfterEof);
    }

    #[test]
    fn empty_token_stream_is_parse_error() {
        let err = parse(&[]).unwrap_err();
        assert_eq!(err.kind, ParseErrorKind::MissingEof);
        assert_eq!(err.span, SourceSpan::new(0, 0));
    }

    #[test]
    fn unsupported_metacharacters_do_not_gain_syntax() {
        // The lexer keeps these characters as ordinary word content, and the
        // parser must not invent shell meaning for them.
        for source in ["echo a|b;c>d", "echo a>b", "echo $HOME", "echo a*b&c"] {
            let program = parse_source(source);
            assert_eq!(program.commands.len(), 1, "for {source:?}");
            let words = &program.commands[0].words;
            assert_eq!(words.len(), 2, "for {source:?}");
            assert!(words.iter().all(|w| matches!(w, Argument::Word { .. })));
        }
        let program = parse_source("echo a|b;c>d");
        assert_eq!(program.commands[0].words[1].value(), "a|b;c>d");
    }
}
