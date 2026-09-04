//! Source input model: an owning source text plus byte-offset spans.

/// An owning snapshot of one AN Shell source text.
///
/// The lexer reads from a SourceText and produces spans into it; a future
/// parser milestone can keep the same spans alive. All offsets are **byte
/// offsets** into the UTF-8 encoding of the text, never raw `char` or grapheme
/// indices (see SourceSpan).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SourceText {
    text: String,
}

impl SourceText {
    /// Wraps `text` as a source input.
    pub fn new(text: impl Into<String>) -> Self {
        SourceText { text: text.into() }
    }

    /// The underlying source text.
    pub fn as_str(&self) -> &str {
        &self.text
    }

    /// Total length in bytes (the maximum valid byte offset).
    pub fn len(&self) -> usize {
        self.text.len()
    }

    /// True when the source text is empty.
    pub fn is_empty(&self) -> bool {
        self.text.is_empty()
    }

    /// Returns the source substring covered by `span`.
    ///
    /// Slicing only ever occurs on character boundaries: the lexer guarantees
    /// every span it produces starts and ends on a UTF-8 boundary. If `span`
    /// is malformed or misaligned this returns `""` instead of panicking.
    pub fn slice(&self, span: SourceSpan) -> &str {
        self.text.get(span.start..span.end).unwrap_or("")
    }
}

/// A half-open `[start, end)` region of a source text.
///
/// Both fields are **byte offsets** into the UTF-8 source text: `end` is
/// exclusive, and slicing must occur on character boundaries. Using byte
/// offsets keeps lexer movement simple and UTF-8 safe without claiming
/// Unicode-grapheme semantics.
#[derive(Copy, Clone, Debug, PartialEq, Eq)]
pub struct SourceSpan {
    /// Inclusive start byte offset.
    pub start: usize,
    /// Exclusive end byte offset.
    pub end: usize,
}

impl SourceSpan {
    /// Creates a half-open byte span `[start, end)`.
    pub const fn new(start: usize, end: usize) -> Self {
        SourceSpan { start, end }
    }

    /// Number of bytes covered.
    pub fn len(self) -> usize {
        self.end.saturating_sub(self.start)
    }

    /// True for a zero-width span (`start == end`).
    pub fn is_empty(self) -> bool {
        self.start == self.end
    }
}
