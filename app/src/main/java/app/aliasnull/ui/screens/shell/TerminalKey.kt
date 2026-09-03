package app.aliasnull.ui.screens.shell

/**
 * A key (or key-pair) from the on-screen terminal shortcut bar.
 *
 * The bar renders two fixed rows of buttons (see [ShellShortcutBar]) and routes
 * presses to [ShellViewModel.onExtraKey]. The behavior is honest about the
 * current frontend input model - no real PTY is connected:
 *
 *  - [UP]/[DOWN] browse the active session's command history.
 *  - [HOME]/[END] move the input caret to the start/end of the line.
 *  - [LEFT]/[RIGHT] move the input caret by one character.
 *  - [TAB] inserts a tab, [SLASH] inserts "/".
 *  - [BRACES]/[PARENS]/[BRACKETS] insert an opened pair with the caret inside.
 *  - [ESC] cancels an in-progress history recall.
 *  - [CTRL]/[ALT] are modifier placeholders: no runtime yet, so they carry no
 *    terminal meaning and stay as the modifier-key foundation for a future
 *    shell backend.
 *
 * The MENU and KEYBOARD shortcuts are UI actions (open the sessions drawer,
 * request the software keyboard) and are handled by the screen rather than
 * through this enum.
 */
enum class TerminalKey {
    ESC,
    CTRL,
    ALT,
    TAB,
    HOME,
    END,
    LEFT,
    UP,
    DOWN,
    RIGHT,
    SLASH,
    BRACES,
    PARENS,
    BRACKETS,
}
