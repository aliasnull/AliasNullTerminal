package app.aliasnull.ui.screens.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aliasnull.R

/**
 * Two fixed rows of terminal shortcuts above the Android keyboard area.
 *
 * The rows never scroll horizontally: every cell shares the row width equally so
 * the layout stays compact and responsive across screen sizes. Presses are
 * routed honestly through the frontend input model via [ShellViewModel.onExtraKey]
 * (ESC cancels a history recall, HOME/END move the caret to the line ends,
 * LEFT/RIGHT move the caret by one character, TAB and "/" insert text, the
 * bracket keys insert a pair, and UP/DOWN browse history). MENU opens the
 * sessions drawer; KEYBOARD re-requests focus on the terminal prompt to bring
 * the software keyboard back.
 *
 * Row 1: ESC CTRL ALT TAB KEYBOARD HOME UP END
 * Row 2: MENU {} () [] / LEFT DOWN RIGHT
 */
@Composable
fun ShellShortcutBar(
    onKey: (TerminalKey) -> Unit,
    onOpenSessions: () -> Unit,
    onShowKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = modifier.fillMaxWidth()) {
            ShortcutRow {
                ShortcutKey(label = "ESC") { onKey(TerminalKey.ESC) }
                ShortcutKey(label = "CTRL") { onKey(TerminalKey.CTRL) }
                ShortcutKey(label = "ALT") { onKey(TerminalKey.ALT) }
                ShortcutKey(label = "TAB") { onKey(TerminalKey.TAB) }
                ShortcutKey(
                    icon = Icons.Filled.Keyboard,
                    contentDescription = stringResource(R.string.shell_show_keyboard),
                    onClick = onShowKeyboard,
                )
                ShortcutKey(label = "HOME") { onKey(TerminalKey.HOME) }
                ShortcutKey(
                    icon = Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.shell_key_up),
                    onClick = { onKey(TerminalKey.UP) },
                )
                ShortcutKey(label = "END") { onKey(TerminalKey.END) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ShortcutRow {
                ShortcutKey(
                    icon = Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.shell_open_sessions),
                    onClick = onOpenSessions,
                )
                ShortcutKey(label = "{}") { onKey(TerminalKey.BRACES) }
                ShortcutKey(label = "()") { onKey(TerminalKey.PARENS) }
                ShortcutKey(label = "[]") { onKey(TerminalKey.BRACKETS) }
                ShortcutKey(label = "/") { onKey(TerminalKey.SLASH) }
                ShortcutKey(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.shell_key_left),
                    onClick = { onKey(TerminalKey.LEFT) },
                )
                ShortcutKey(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.shell_key_down),
                    onClick = { onKey(TerminalKey.DOWN) },
                )
                ShortcutKey(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.shell_key_right),
                    onClick = { onKey(TerminalKey.RIGHT) },
                )
            }
        }
    }
}

@Composable
private fun ShortcutRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun RowScope.ShortcutKey(
    label: String? = null,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = if (pressed) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (pressed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(36.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Text(
                text = label.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = contentColor,
                ),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}
