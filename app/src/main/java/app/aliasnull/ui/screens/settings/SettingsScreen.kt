package app.aliasnull.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aliasnull.R
import app.aliasnull.ui.components.SectionPlaceholder

/**
 * UI foundation for the Settings section. No functional settings exist yet;
 * this screen only provides a structured home for future configuration.
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    SectionPlaceholder(
        icon = Icons.Filled.Settings,
        title = stringResource(R.string.nav_settings),
        identity = stringResource(R.string.settings_identity),
        description = stringResource(R.string.settings_description),
        plannedScope = listOf(
            stringResource(R.string.settings_planned_appearance),
            stringResource(R.string.settings_planned_shell),
            stringResource(R.string.settings_planned_runtime),
            stringResource(R.string.settings_planned_packages),
            stringResource(R.string.settings_planned_desktop),
        ),
        modifier = modifier,
    )
}
