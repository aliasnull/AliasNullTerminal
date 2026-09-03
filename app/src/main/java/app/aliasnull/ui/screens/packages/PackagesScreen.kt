package app.aliasnull.ui.screens.packages

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aliasnull.R
import app.aliasnull.ui.components.SectionPlaceholder

/**
 * UI foundation for the Packages section. The ALIA package manager is not
 * implemented in this build; this screen only frames the future area.
 */
@Composable
fun PackagesScreen(modifier: Modifier = Modifier) {
    SectionPlaceholder(
        icon = Icons.Filled.Inventory2,
        title = stringResource(R.string.nav_packages),
        identity = stringResource(R.string.packages_identity),
        description = stringResource(R.string.packages_description),
        plannedScope = listOf(
            stringResource(R.string.packages_planned_repositories),
            stringResource(R.string.packages_planned_install),
            stringResource(R.string.packages_planned_dependencies),
            stringResource(R.string.packages_planned_database),
        ),
        modifier = modifier,
    )
}
