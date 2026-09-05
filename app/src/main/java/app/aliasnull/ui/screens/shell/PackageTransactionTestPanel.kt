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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aliasnull.shell.bootstrap.PackageTransactionDiagnosticResult
import app.aliasnull.shell.bootstrap.PackageTransactionTestKind

/** Color for a genuinely met expectation (PASS); no Material success role exists. */
private val PackagePassColor = Color(0xFF4CAF50)

/**
 * Labeled developer diagnostic panel (Part 27-V-DEVICE-DIAGNOSTIC) shown under the
 * Shell's native-runtime test panel: it runs exactly one authorized
 * package-transaction case against the real [app.aliasnull.shell.bootstrap.PackageTransaction]
 * engine and shows the genuine structured result.
 *
 * This is deliberately NOT a Shell command surface and NOT a package manager. The
 * only choice the UI makes is a [PackageTransactionTestKind] enum case; the panel
 * never accepts a package name, manifest, payload, path, executable or any user
 * input, and nothing is routed through a session, the command executor or the
 * native process seam. Each case drives the real production install transaction
 * against a dedicated disposable app-private root and the outcome is presented
 * truthfully: a case is PASS only when its stated expectation was genuinely met
 * by the transaction's outcome and the inspected on-disk postconditions. At most
 * one case runs at a time; while one runs every button is disabled, and no fake
 * progress is ever shown - a run simply reports its one real result.
 */
@Composable
fun PackageTransactionTestPanel(
    state: PackageTransactionTestUiState,
    onRun: (PackageTransactionTestKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PACKAGE TRANSACTION TEST",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    text = "  developer diagnostic",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            val enabled = state.runningCase == null
            // Two rows of four keep the eight labels legible on a phone, matching
            // the native-panel button styling (equal-weight chips, monospace).
            enumValues<PackageTransactionTestKind>().toList().chunked(4).forEach { rowCases ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    rowCases.forEach { kind ->
                        PackageCaseButton(
                            kind = kind,
                            enabled = enabled,
                            running = state.runningCase == kind,
                            onClick = { onRun(kind) },
                        )
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            PackageStatusBlock(state)
        }
    }
}

/** One package-transaction case button; disabled while any case is running. */
@Composable
private fun RowScope.PackageCaseButton(
    kind: PackageTransactionTestKind,
    enabled: Boolean,
    running: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        running || pressed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        running || pressed -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        Text(
            text = kind.title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = contentColor,
            ),
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Clip,
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
        )
    }
}

/** The truthful status area: idle hint, running line, or the last structured result. */
@Composable
private fun PackageStatusBlock(state: PackageTransactionTestUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val running = state.runningCase
        val result = state.result
        when {
            running != null -> {
                Text(
                    text = "Running ${running.title}: ${running.expectedOutcome}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "The real package transaction is running on a disposable app-private root.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result == null -> {
                Text(
                    text = "No package transaction test has run yet. Each case runs the real " +
                        "transaction on a dedicated disposable root.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> PackageResultLines(result)
        }
    }
}

/** Renders one structured result truthfully: PASS only when expectation was met. */
@Composable
private fun PackageResultLines(result: PackageTransactionDiagnosticResult) {
    val title = result.kind.title
    val expected = result.kind.expectedOutcome
    if (result.passed) {
        Text(
            text = "PASS - $title: ${result.outcomeLine}",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = PackagePassColor,
        )
    } else {
        Text(
            text = "FAIL - $title: expected '$expected', observed '${result.outcomeLine}'",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.error,
        )
    }
    result.detailLines.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
