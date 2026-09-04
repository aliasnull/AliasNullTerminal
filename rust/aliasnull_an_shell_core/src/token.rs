//! The minimal AN Shell token vocabulary produced by the Part 27-C lexer.

use crate::source::SourceSpan;

/// The lexical category of one Token.
///
/// Only the smallest genuinely-required vocabulary for this milestone is
/// modelled. There is deliberately no operator/punctuation vocabulary: a
/// character such as `|`, `>`, `;` or `&` has no special syntax yet, so under
/// the lexer's delimiter rules it is ordinary word content (documented in the
/// lexer). The two data-bearing kinds (Word, Str) each carry their content and
/// keep it clearly separate from the source span, which locates the raw text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TokenKind {
    /// An ordinary word: contiguous non-whitespace, non-newline, non-quote
    /// text. Carries the raw text, which equals the source slice of its span.
    Word(String),
    /// A double-quoted string. Carries the interpreted content (the source
    /// between the surrounding quotes, without the quote characters). The
    /// span includes the surrounding quotes.
    Str(String),
    /// One `'\n'`. Option A of the Part 27-C newline decision: newlines are
    /// represented explicitly so future line-oriented shell parsing can rely
    /// on them instead of re-deriving line breaks from whitespace.
    Newline,
    /// End of input. The lexer always emits exactly one trailing Eof token.
    Eof,
}

/// One lexed token: a TokenKind plus the source span it came from.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Token {
    /// What was lexed.
    pub kind: TokenKind,
    /// Where in the original source the token came from (byte offsets).
    pub span: SourceSpan,
}

impl Token {
    /// Creates a token of `kind` located at `span`.
    pub fn new(kind: TokenKind, span: SourceSpan) -> Self {
        Token { kind, span }
    }
}
