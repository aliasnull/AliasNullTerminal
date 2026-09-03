package app.aliasnull.ui.screens.files

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aliasnull.R
import app.aliasnull.ui.components.SectionPlaceholder

/**
 * UI foundation for the Files section. The AliasNull virtual filesystem and a
 * file manager are not implemented in this build; this screen only frames the
 * future area and never touches device storage.
 */
@Composable
fun FilesScreen(modifier: Modifier = Modifier) {
    SectionPlaceholder(
        icon = Icons.Filled.Folder,
        title = stringResource(R.string.nav_files),
        identity = stringResource(R.string.files_identity),
        description = stringResource(R.string.files_description),
        plannedScope = listOf(
            stringResource(R.string.files_planned_layout),
            stringResource(R.string.files_planned_browser),
            stringResource(R.string.files_planned_backends),
        ),
        modifier = modifier,
    )
}
