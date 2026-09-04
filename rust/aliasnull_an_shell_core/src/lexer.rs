//! The Part 27-C AN Shell lexer: source text -> token stream.
//!
//! This lexer only recognises a deliberately minimal subset and makes no claim
//! of being a complete AN Shell grammar (none exists in the repository; the
//! vocabulary introduced here is a new minimal foundation). Its rules are
//! documented below and on `lex`. It executes nothing and performs no process,
//! PTY, JNI or filesystem action.

use crate::source::{SourceSpan, SourceText};
use crate::token::{Token, TokenKind};

/// The kind of a lexing failure.
///
/// Only one genuine error exists in this milestone: an unterminated
/// double-quoted string. Normal malformed user input is reported here instead
/// of panicking or fabricating a successful token.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LexerErrorKind {
    /// A `"` was opened but the source ended before a closing `"`.
    UnterminatedString,
}

/// A lexing failure carrying enough source information to locate it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LexerError {
    /// Which lexical rule failed.
    pub kind: LexerErrorKind,
    /// The offending region. For an unterminated string this spans from the
    /// opening quote to the end of the source.
    pub span: SourceSpan,
}

impl LexerError {
    /// Creates a lexing error.
    pub const fn new(kind: LexerErrorKind, span: SourceSpan) -> Self {
        LexerError { kind, span }
    }
}

impl core::fmt::Display for LexerError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self.kind {
            LexerErrorKind::UnterminatedString => {
                write!(
                    f,
                    "unterminated double-quoted string at byte {}",
                    self.span.start
                )
            }
        }
    }
}

impl std::error::Error for LexerError {}

/// Tokenizes `source` into an owned token stream.
///
/// Whitespace rules
/// ----------------
/// * ASCII horizontal whitespace (` `, tab, carriage return) separates tokens
///   and never becomes a token itself. Unicode whitespace other than these
///   three is ordinary word content.
/// * `'\n'` produces an explicit Newline token (the chosen Option A).
///
/// Word rules
/// ----------
/// A word is contiguous text that stops at ASCII horizontal whitespace, at a
/// newline, or at `"`. Every other character is ordinary word content; in
/// particular characters that will later carry shell syntax (`|`, `>`, `;`,
/// `&`, `$`, `*`, ...) have no special meaning yet and stay inside words. This
/// is the explicit Part 27-C boundary and is not partial shell syntax.
///
/// String rules
/// ------------
/// A `"` opens a double-quoted string. Content is the text until the next `"`;
/// newlines and backslashes inside a string are literal content (no escape
/// processing is implemented). A source that ends before a closing quote is an
/// error. Only double quotes are recognised; a single quote is ordinary word
/// content in this milestone.
///
/// UTF-8
/// -----
/// The lexer advances by full UTF-8 characters, so it never splits a
/// multi-byte character and never corrupts non-ASCII input. Only ASCII
/// characters are treated as delimiters. No Unicode-grapheme semantics are
/// claimed.
///
/// Completion
/// ----------
/// The returned stream always ends with exactly one trailing Eof token located
/// at the end of the source, so an empty or whitespace-only input completes
/// cleanly with just that token.
pub fn lex(source: &SourceText) -> Result<Vec<Token>, LexerError> {
    let bytes = source.as_str().as_bytes();
    let len = bytes.len();
    let mut tokens = Vec::new();
    let mut pos = 0usize;

    while pos < len {
        match bytes[pos] {
            // ASCII horizontal whitespace: skip.
            b' ' | b'\t' | b'\r' => pos += 1,

            // Newline -> explicit Newline token (Option A).
            b'\n' => {
                tokens.push(Token::new(TokenKind::Newline, SourceSpan::new(pos, pos + 1)));
                pos += 1;
            }

            // Double quote -> quoted string.
            b'"' => {
                let start = pos;
                pos += 1; // consume the opening quote.
                let content_start = pos;
                loop {
                    if pos >= len {
                        return Err(LexerError::new(
                            LexerErrorKind::UnterminatedString,
                            SourceSpan::new(start, len),
                        ));
                    }
                    if bytes[pos] == b'"' {
                        break;
                    }
                    pos += utf8_len(bytes[pos]);
                }
                let content_end = pos;
                pos += 1; // consume the closing quote.
                let content =
                    source.slice(SourceSpan::new(content_start, content_end)).to_string();
                tokens.push(Token::new(TokenKind::Str(content), SourceSpan::new(start, pos)));
            }

            // Any other character starts a word.
            _ => {
                let start = pos;
                loop {
                    if pos >= len {
                        break;
                    }
                    let b = bytes[pos];
                    if matches!(b, b' ' | b'\t' | b'\r' | b'\n' | b'"') {
                        break;
                    }
                    pos += utf8_len(b);
                }
                let content = source.slice(SourceSpan::new(start, pos)).to_string();
                tokens.push(Token::new(TokenKind::Word(content), SourceSpan::new(start, pos)));
            }
        }
    }

    tokens.push(Token::new(TokenKind::Eof, SourceSpan::new(len, len)));
    Ok(tokens)
}

/// Byte width of the UTF-8 character whose leading byte is `b`. `b` is always
/// a character start (the lexer never positions `pos` inside a multi-byte
/// character), so this is well defined for any valid `&str`.
fn utf8_len(b: u8) -> usize {
    match b {
        0x00..=0x7F => 1,
        0xC0..=0xDF => 2,
        0xE0..=0xEF => 3,
        _ => 4,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lex_text(input: &str) -> Result<Vec<Token>, LexerError> {
        lex(&SourceText::new(input))
    }

    fn word(s: &str) -> TokenKind {
        TokenKind::Word(s.to_string())
    }

    fn string(s: &str) -> TokenKind {
        TokenKind::Str(s.to_string())
    }

    #[test]
    fn empty_input_completes_with_eof() {
        let toks = lex_text("").unwrap();
        assert_eq!(toks, vec![Token::new(TokenKind::Eof, SourceSpan::new(0, 0))]);
    }

    #[test]
    fn single_word() {
        let toks = lex_text("echo").unwrap();
        assert_eq!(toks.len(), 2);
        assert_eq!(toks[0].kind, word("echo"));
        assert_eq!(toks[0].span, SourceSpan::new(0, 4));
        assert_eq!(toks[1].kind, TokenKind::Eof);
        assert_eq!(toks[1].span, SourceSpan::new(4, 4));
    }

    #[test]
    fn multiple_words_separated_by_spaces() {
        let toks = lex_text("echo hello world").unwrap();
        let kinds: Vec<_> = toks.iter().map(|t| t.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![word("echo"), word("hello"), word("world"), TokenKind::Eof]
        );
    }

    #[test]
    fn leading_and_trailing_whitespace() {
        let toks = lex_text("  echo  ").unwrap();
        assert_eq!(toks.len(), 2);
        assert_eq!(toks[0].kind, word("echo"));
        // "  echo  " -> echo occupies bytes 2..6, trailing spaces are skipped.
        assert_eq!(toks[0].span, SourceSpan::new(2, 6));
        assert_eq!(toks[1].kind, TokenKind::Eof);
        assert_eq!(toks[1].span, SourceSpan::new(8, 8));
    }

    #[test]
    fn consecutive_whitespace_collapses() {
        let toks = lex_text("a\t\tb").unwrap();
        let kinds: Vec<_> = toks.iter().map(|t| t.kind.clone()).collect();
        assert_eq!(kinds, vec![word("a"), word("b"), TokenKind::Eof]);
    }

    #[test]
    fn newline_is_an_explicit_token() {
        let toks = lex_text("a\nb").unwrap();
        let kinds: Vec<_> = toks.iter().map(|t| t.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![word("a"), TokenKind::Newline, word("b"), TokenKind::Eof]
        );
        assert_eq!(toks[1].span, SourceSpan::new(1, 2));
    }

    #[test]
    fn blank_lines_each_produce_newline() {
        let toks = lex_text("a\n\nb").unwrap();
        let kinds: Vec<_> = toks.iter().map(|t| t.kind.clone()).collect();
        assert_eq!(
            kinds,
            vec![
                word("a"),
                TokenKind::Newline,
                TokenKind::Newline,
                word("b"),
                TokenKind::Eof
            ]
        );
    }

    #[test]
    fn basic_double_quoted_string() {
        let src = "\"hello world\"";
        let toks = lex_text(src).unwrap();
        assert_eq!(toks.len(), 2);
        assert_eq!(toks[0].kind, string("hello world"));
        assert_eq!(toks[0].span, SourceSpan::new(0, src.len()));
    }

    #[test]
    fn string_value_is_distinct_from_span_slice() {
        let src = "\"hi\"";
        let toks = lex_text(src).unwrap();
        assert_eq!(toks[0].kind, string("hi"));
        // The source slice of the whole token span still includes the quotes.
        assert_eq!(SourceText::new(src).slice(toks[0].span), "\"hi\"");
    }

    #[test]
    fn newline_inside_quotes_is_literal_content() {
        let toks = lex_text("\"a\nb\"").unwrap();
        assert_eq!(toks[0].kind, string("a\nb"));
    }

    #[test]
    fn backslash_is_literal_inside_quotes_no_escape_processing() {
        // Chosen escape behavior: backslash inside a double-quoted string is
        // ordinary content (no escape processing). It does not escape the
        // closing quote.
        let toks = lex_text("\"a\\b\"").unwrap();
        assert_eq!(toks[0].kind, string("a\\b"));
        assert_eq!(toks[0].span, SourceSpan::new(0, 5));
        // `"a\"` closes at the second quote; the backslash is content, not an
        // escape that would make that quote literal, so no error is produced.
        let toks = lex_text("\"a\\\"").unwrap();
        assert_eq!(toks[0].kind, string("a\\"));
        assert_eq!(toks[1].kind, TokenKind::Eof);
    }

    #[test]
    fn unterminated_quote_is_an_error() {
        let err = lex_text("\"hello").unwrap_err();
        assert_eq!(err.kind, LexerErrorKind::UnterminatedString);
        assert_eq!(err.span, SourceSpan::new(0, 6));
    }

    #[test]
    fn unterminated_quote_after_a_word_locations_the_error() {
        let err = lex_text("x\"abc").unwrap_err();
        assert_eq!(err.kind, LexerErrorKind::UnterminatedString);
        assert_eq!(err.span, SourceSpan::new(1, 5));
    }

    #[test]
    fn utf8_input_is_not_corrupted() {
        let src = "hello दुनिया";
        let toks = lex_text(src).unwrap();
        assert_eq!(toks.len(), 3);
        assert_eq!(toks[0].kind, word("hello"));
        assert_eq!(toks[0].span, SourceSpan::new(0, 5));
        // 'hello ' is 6 bytes; the Devanagari word spans the remaining bytes.
        assert_eq!(toks[1].kind, word("दुनिया"));
        assert_eq!(toks[1].span, SourceSpan::new(6, src.len()));
        assert_eq!(toks[2].kind, TokenKind::Eof);
    }

    #[test]
    fn unsupported_metachars_remain_word_content() {
        // Pipes/redirects/semicolons have no syntax yet (Part 27-C boundary);
        // under the delimiter rules they are ordinary word content.
        let toks = lex_text("echo a|b;c>d").unwrap();
        let kinds: Vec<_> = toks.iter().map(|t| t.kind.clone()).collect();
        assert_eq!(kinds, vec![word("echo"), word("a|b;c>d"), TokenKind::Eof]);
    }

    #[test]
    fn ascii_span_offsets_are_byte_exact() {
        let toks = lex_text("cat /p").unwrap();
        assert_eq!(toks[0].span, SourceSpan::new(0, 3)); // cat
        assert_eq!(toks[1].span, SourceSpan::new(4, 6)); // /p
        assert_eq!(toks[2].kind, TokenKind::Eof);
    }
}
