package app.aliasnull.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector
import app.aliasnull.R

/**
 * The top-level application sections reachable from the bottom navigation bar.
 *
 * Route strings, labels and icons are centralized here so screens never hard
 * code a route. Adding a future section is one new entry plus a matching
 * destination in [AliasNullNavHost].
 */
enum class AliasNullDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(route = "home", labelRes = R.string.nav_home, icon = Icons.Filled.Home),
    Shell(route = "shell", labelRes = R.string.nav_shell, icon = Icons.Filled.Terminal),
    Packages(route = "packages", labelRes = R.string.nav_packages, icon = Icons.Filled.Inventory2),
    Files(route = "files", labelRes = R.string.nav_files, icon = Icons.Filled.Folder),
    Settings(route = "settings", labelRes = R.string.nav_settings, icon = Icons.Filled.Settings),
}
