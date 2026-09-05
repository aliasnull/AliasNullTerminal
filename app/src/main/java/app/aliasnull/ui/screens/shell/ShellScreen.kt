package app.aliasnull.ui.screens.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aliasnull.shell.runtime.ShellBackendPhase
import kotlinx.coroutines.launch

private const val Prompt = "$"
private const val LivePromptLineKey = "live-prompt"

/**
 * Terminal-style Shell for AliasNull.
 *
 * The Shell's main area shows exactly one of three things, chosen by the runtime
 * gate the ViewModel observes ([ShellBackendPhase]):
 *
 *   - INITIALIZING: a truthful, non-interactive pane while the runtime bootstraps
 *     and verifies the AN Shell core (no fake progress, no fabricated delay, no
 *     command input);
 *   - FAILED: "Unable to start the AN Shell" with the runtime's user-safe reason
 *     and a Retry action that re-runs the real verification;
 *   - READY: the interactive terminal, described below.
 *
 * The terminal area is one continuous, dark, text-focused terminal surface:
 * rendered history lines followed by a live prompt line ("$ ") that carries the
 * current typing. There is no separate input box or message-style send region.
 * Session management (switch / create / close) lives in a hidden left drawer
 * opened from a trigger in the shortcut row below the terminal. Multiple
 * independent sessions are backed by [ShellViewModel]; only the active session is
 * rendered. Commands route through the AN Shell core and are accepted only while
 * the gate is READY; the Shell never falls back to a built-in command set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellScreen(
    modifier: Modifier = Modifier,
) {
    val viewModel = rememberActivityShellViewModel()
    val state by viewModel.uiState.collectAsState()
    val active = state.activeSession ?: return
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val inputFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current

    // Puts focus on the one live prompt and opens the keyboard. The prompt can be
    // scrolled out of composition after reading far up the history, so it is
    // reached before focus is requested. Shared by the KEYBOARD shortcut and by a
    // tap anywhere on the terminal surface; both funnel through here.
    val focusTerminalInput: () -> Unit = {
        scope.launch {
            listState.animateScrollToItem(active.entries.size)
            inputFocusRequester.requestFocus()
            keyboard?.show()
        }
    }

    // Opening the sessions drawer must never leave its bottom actions (New
    // session) trapped behind the software keyboard, so dismiss the keyboard
    // while the drawer is shown. The sheet is also IME-aware for the animation.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) keyboard?.hide()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = state.sessions,
                activeId = active.id,
                onSelect = { id ->
                    viewModel.switchSession(id)
                    scope.launch { drawerState.close() }
                },
                onNewSession = {
                    viewModel.createSession()
                    scope.launch { drawerState.close() }
                },
                onCloseSession = viewModel::closeSession,
            )
        },
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
        ) {
            when (state.runtimeStatus.phase) {
                ShellBackendPhase.INITIALIZING -> ShellInitializingPane(Modifier.weight(1f))
                ShellBackendPhase.FAILED -> ShellFailedStartPane(
                    reason = state.runtimeStatus.failureMessage,
                    onRetry = viewModel::retryInitialize,
                    modifier = Modifier.weight(1f),
                )
                ShellBackendPhase.READY -> {
                    TerminalSurface(
                        session = active,
                        focusRequester = inputFocusRequester,
                        listState = listState,
                        onTap = focusTerminalInput,
                        onInputChange = viewModel::onInputChanged,
                        onSubmit = viewModel::submitCommand,
                        onHistoryPrevious = viewModel::previousCommand,
                        onHistoryNext = viewModel::nextCommand,
                        modifier = Modifier.weight(1f),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ShellShortcutBar(
                        onKey = viewModel::onExtraKey,
                        onOpenSessions = { scope.launch { drawerState.open() } },
                        onShowKeyboard = focusTerminalInput,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NativeProcessTestPanel(
                        state = state.nativeProcessTest,
                        onRun = viewModel::runNativeProcessTest,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PackageTransactionTestPanel(
                        state = state.packageTransactionTest,
                        onRun = viewModel::runPackageTransactionTest,
                    )
                }
            }
        }
    }
}

/**
 * Truthful, non-interactive pane shown while the runtime bootstraps and verifies
 * the AN Shell core ([ShellBackendPhase.INITIALIZING]). The lines correspond to
 * real work only - bootstrapping the runtime, verifying the core - never a fake
 * percentage or an arbitrary delay, and the Shell accepts no command input here.
 */
@Composable
private fun ShellInitializingPane(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Initializing the AliasNull runtime...\nPreparing the AliasNull base userspace...\nVerifying the AN Shell core...",
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Pane shown when the runtime's real verification attempt finished without a
 * READY AN Shell core ([ShellBackendPhase.FAILED]). It shows the runtime's
 * user-safe [reason] and a Retry action that re-runs that real verification
 * ([ShellViewModel.retryInitialize]); READY is never manufactured by the UI.
 */
@Composable
private fun ShellFailedStartPane(
    reason: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Unable to start the AN Shell",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.error,
            )
            if (!reason.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

/**
 * Shell sessions are kept alive for the whole process, not just for the current
 * tab entry. Bottom-bar navigation pops the Shell entry (and with it its
 * ViewModel store) when the user leaves the tab, which would otherwise discard
 * every session; scoping to the Activity keeps them intact while the process
 * runs.
 */
@Composable
private fun rememberActivityShellViewModel(): ShellViewModel {
    val owner = LocalContext.current as? ViewModelStoreOwner
    return if (owner != null) {
        viewModel(viewModelStoreOwner = owner)
    } else {
        viewModel()
    }
}

/**
 * The scrollable terminal. Rendered history fills the list; the current prompt
 * and input are the last item, so typing happens inline in the terminal stream
 * rather than in a separate panel. The list auto-scrolls toward the prompt when
 * a session becomes active or new output lands, but never forces the reader back
 * down while they are reading history.
 */
@Composable
private fun TerminalSurface(
    session: TerminalSession,
    focusRequester: FocusRequester,
    listState: LazyListState,
    onTap: () -> Unit,
    onInputChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onHistoryPrevious: () -> Unit,
    onHistoryNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val promptIndex = session.entries.size

    // Jump to the live prompt when the active session or its content changes,
    // so new output is always revealed at the bottom. Reading history upward is
    // never forced back down: nothing below listens to the scroll position.
    LaunchedEffect(session.id, promptIndex) {
        listState.scrollToItem(promptIndex)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            // A clean tap anywhere on the terminal focuses the single live prompt.
            // detectTapGestures only reports a tap once the gesture is confirmed,
            // so drags still scroll history without opening the keyboard. The
            // session id + prompt index key the handler so a switch of session or
            // growth of history re-captures the current prompt position.
            .pointerInput(session.id, promptIndex) {
                detectTapGestures(onTap = { onTap() })
            },
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(session.entries, key = { it.id }) { entry ->
            TerminalLine(entry)
        }
        item(key = LivePromptLineKey) {
            LivePromptLine(
                value = session.input,
                focusRequester = focusRequester,
                onValueChange = onInputChange,
                onSubmit = onSubmit,
                onHistoryPrevious = onHistoryPrevious,
                onHistoryNext = onHistoryNext,
            )
        }
    }
}

@Composable
private fun TerminalLine(entry: TerminalEntry) {
    val baseColor = when (entry.type) {
        TerminalEntryType.COMMAND, TerminalEntryType.OUTPUT -> MaterialTheme.colorScheme.onSurface
        TerminalEntryType.ERROR -> MaterialTheme.colorScheme.error
        TerminalEntryType.SYSTEM -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    val text = buildAnnotatedString {
        if (entry.type == TerminalEntryType.COMMAND) {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                append("$Prompt ")
            }
        }
        append(entry.content)
    }
    Text(
        text = text,
        style = style,
        color = baseColor,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The live "$ " prompt and its editable input, rendered as the terminal's last
 * line. Only the input region is editable; the prompt glyph and every history
 * line above are plain text. Tapping the prompt (or the input area) requests
 * focus, which brings up the Android keyboard.
 */
@Composable
private fun LivePromptLine(
    value: TextFieldValue,
    focusRequester: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onSubmit: () -> Unit,
    onHistoryPrevious: () -> Unit,
    onHistoryNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Prompt,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { focusRequester.requestFocus() },
            ),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.DirectionUp -> {
                                onHistoryPrevious(); true
                            }
                            Key.DirectionDown -> {
                                onHistoryNext(); true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        )
    }
}
