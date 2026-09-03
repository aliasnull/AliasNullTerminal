package app.aliasnull.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import app.aliasnull.ui.screens.files.FilesScreen
import app.aliasnull.ui.screens.home.HomeScreen
import app.aliasnull.ui.screens.packages.PackagesScreen
import app.aliasnull.ui.screens.settings.SettingsScreen
import app.aliasnull.ui.screens.shell.ShellScreen

/**
 * Selecting a top-level section never stacks duplicates: the tab graph is
 * popped back to the start destination with its state saved, and the newly
 * selected tab reuses a previously saved entry when one exists. This is the
 * standard Navigation Compose bottom-bar pattern.
 */
fun NavHostController.navigateToTopLevel(destination: AliasNullDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Declares every top-level destination and hosts the five AliasNull sections.
 * The host sits above the app Scaffold's bottom bar, so [contentPadding] keeps
 * screen content clear of the bar and system insets.
 */
@Composable
fun AliasNullNavHost(
    navController: NavHostController,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = AliasNullDestination.Home.route,
        modifier = modifier.fillMaxSize().padding(contentPadding),
    ) {
        composable(AliasNullDestination.Home.route) {
            HomeScreen(onOpenSection = navController::navigateToTopLevel)
        }
        composable(AliasNullDestination.Shell.route) {
            ShellScreen()
        }
        composable(AliasNullDestination.Packages.route) {
            PackagesScreen()
        }
        composable(AliasNullDestination.Files.route) {
            FilesScreen()
        }
        composable(AliasNullDestination.Settings.route) {
            SettingsScreen()
        }
    }
}
