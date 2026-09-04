//! The minimal AN Shell syntax AST produced by the Part 27-D parser.
//!
//! This is an initial AN Shell *syntax* AST, not an executable runtime
//! representation. The nodes are passive data: they record the structure of a
//! parsed command line and where each piece came from, and nothing here
//! executes. The AST knows nothing about Android, Kotlin, JNI, execution
//! routing, terminal sessions or processes.

use crate::source::SourceSpan;

/// A complete parsed AN Shell source input.
///
/// `commands` holds the commands found between the lexer's separator newlines.
/// Empty and whitespace-only inputs parse to a `Program` with zero commands;
/// blank lines never become empty `Command` nodes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Program {
    /// The parsed commands, in source order.
    pub commands: Vec<Command>,
    /// Source region of the whole program.
    ///
    /// Span policy: `[0, 0)` when `commands` is empty (an empty program has no
    /// real source region); otherwise the first command's start through the
    /// last command's end.
    pub span: SourceSpan,
}

/// One line-oriented AN Shell command: a non-empty run of arguments.
///
/// Syntax only. It carries no execution behaviour, environment, working
/// directory or native handle, and it has no `execute`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Command {
    /// The command's arguments, in source order. Never empty in a parsed
    /// `Program`.
    pub words: Vec<Argument>,
    /// Source region from the first argument's start to the last argument's
    /// end. Separator newlines around the command are excluded.
    pub span: SourceSpan,
}

/// One command argument, keeping the two lexer-produced forms distinct.
///
/// The lexer owns quote handling: it produces the interpreted string content
/// and the token span. The parser consumes those values and never re-scans
/// source or re-parses quotes, so no escape or quote semantics live here.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Argument {
    /// A bare (unquoted) word. `value` equals the source slice of `span`.
    Word {
        /// The word text.
        value: String,
        /// The originating token's source span.
        span: SourceSpan,
    },
    /// A double-quoted string. `value` is the interpreted content without the
    /// surrounding quotes; `span` includes the surrounding quotes.
    String {
        /// The interpreted string content (quotes removed).
        value: String,
        /// The originating token's source span, quotes included.
        span: SourceSpan,
    },
}

impl Argument {
    /// The argument's textual value: the word text, or the interpreted string
    /// content.
    pub fn value(&self) -> &str {
        match self {
            Argument::Word { value, .. } | Argument::String { value, .. } => value.as_str(),
        }
    }

    /// The argument's source span, identical to its originating token span.
    pub fn span(&self) -> SourceSpan {
        match self {
            Argument::Word { span, .. } | Argument::String { span, .. } => *span,
        }
    }
}
