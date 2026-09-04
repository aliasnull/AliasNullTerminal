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
import app.aliasnull.shell.runtime.NativeProcessExecutionResult
import app.aliasnull.shell.runtime.NativeProcessTestKind
import app.aliasnull.shell.runtime.NativeProcessTestResult
import app.aliasnull.shell.runtime.native.NativeProcessOutcome
import app.aliasnull.shell.runtime.native.NativeProcessResult

/** Color for a genuinely met expectation (PASS); no Material success role exists. */
private val PassColor = Color(0xFF4CAF50)

/**
 * Labeled developer diagnostic panel (Part 27-Q) shown under the Shell shortcut
 * bar: it runs exactly one authorized native-process self-check and shows the
 * genuine structured result.
 *
 * This is deliberately NOT a Shell command surface. The only choice the UI makes
 * is a [NativeProcessTestKind] enum case; the panel never builds an argv, working
 * directory, environment or stdin, and nothing is routed through a session or the
 * command executor. Each case runs a real Android host binary through the runtime's
 * controlled seam (policy gate -> native runner -> real stdout/stderr/exit) and the
 * outcome is presented truthfully: a case is PASS only when its stated expectation
 * was genuinely met, a FAIL keeps its real category (policy rejection / runner
 * unavailable / internal failure / native outcome), and a [NativeProcessTestResult.NotReady]
 * result is shown as a readiness fact, never as a pass or fail. At most one case
 * runs at a time; while one runs every button is disabled (no duplicate launch),
 * and no fake progress is ever shown - a run simply reports its one real result.
 */
@Composable
fun NativeProcessTestPanel(
    state: NativeProcessTestUiState,
    onRun: (NativeProcessTestKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "NATIVE RUNTIME TEST",
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                enumValues<NativeProcessTestKind>().forEach { kind ->
                    CaseButton(
                        kind = kind,
                        enabled = state.runningCase == null,
                        running = state.runningCase == kind,
                        onClick = { onRun(kind) },
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            StatusBlock(state)
        }
    }
}

/** One self-check case button; disabled while any case is running. */
@Composable
private fun RowScope.CaseButton(
    kind: NativeProcessTestKind,
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
private fun StatusBlock(state: NativeProcessTestUiState) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val running = state.runningCase
        val result = state.result
        when {
            running != null -> {
                Text(
                    text = "Running ${running.title}: ${running.expectationText}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "A real host process is running; the panel reports its one genuine outcome.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result == null -> {
                Text(
                    text = "No self-check has run yet. Choose a case to run one real native process.",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result is NativeProcessTestResult.NotReady -> {
                Text(
                    text = "${result.kind.title} was not run - native runtime not ready",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            result is NativeProcessTestResult.Outcome -> {
                val passed = result.expectedMet
                Text(
                    text = buildString {
                        append(if (passed) "PASS - " else "FAIL - ")
                        append(result.kind.title)
                        append(": ")
                        append(result.kind.expectationText)
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (passed) PassColor else MaterialTheme.colorScheme.error,
                )
                ExecutionLines(result.execution).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Renders a [NativeProcessExecutionResult] into short monospace lines, categories intact. */
private fun ExecutionLines(execution: NativeProcessExecutionResult): List<String> = when (execution) {
    is NativeProcessExecutionResult.Executed -> processLines(execution.result)
    is NativeProcessExecutionResult.Rejected ->
        listOf("Rejected by policy (${execution.reason.code}): ${execution.reason.message}")
    is NativeProcessExecutionResult.RunnerUnavailable ->
        listOf("Native runner unavailable: ${execution.message}")
    is NativeProcessExecutionResult.InternalFailure ->
        listOf("Internal failure: ${execution.message}")
}

private fun processLines(result: NativeProcessResult): List<String> = buildList {
    when (result.outcome) {
        NativeProcessOutcome.EXITED ->
            add("exit ${result.exitCode ?: "?"}" + if (result.processRan) " (process ran)" else "")
        NativeProcessOutcome.TERMINATED_BY_SIGNAL ->
            add("terminated by signal ${result.termSignal ?: "?"} (process ran)")
        NativeProcessOutcome.LAUNCH_FAILED ->
            add("launch failed: ${result.errorMessage ?: "the executable could not be started"}")
        NativeProcessOutcome.INTERNAL_ERROR ->
            add("runner internal error: ${result.errorMessage ?: "unknown"}")
        NativeProcessOutcome.RUNNER_UNAVAILABLE ->
            add("runner unavailable: ${result.errorMessage ?: "unknown"}")
    }
    val stdout = result.stdout.trimEnd()
    val stderr = result.stderr.trimEnd()
    add("stdout: " + if (stdout.isEmpty()) "(empty)" else stdout)
    add("stderr: " + if (stderr.isEmpty()) "(empty)" else stderr)
}
