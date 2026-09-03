package app.aliasnull

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.aliasnull.navigation.AliasNullDestination
import app.aliasnull.navigation.AliasNullNavHost
import app.aliasnull.navigation.navigateToTopLevel
import app.aliasnull.ui.theme.AliasNullTheme

/**
 * Root composition point of the AliasNull UI.
 *
 * Owns the Compose theme and the application scaffold: a bottom navigation bar
 * over the section NavHost (see AliasNullNavHost) of the five top-level
 * destinations. Kept lightweight - screens and navigation logic live in their
 * own packages. Future adaptive layouts can swap this phone scaffold for a
 * wider one without touching the screens.
 */
@Composable
fun AliasNullApp() {
    AliasNullTheme {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination

        // Tracks whether the software keyboard is open so the bottom navigation
        // bar can step out of the way: while typing, only the Shell (terminal +
        // shortcut rows) should sit between the content and the IME.
        var imeHeight by remember { mutableIntStateOf(0) }
        val keyboardOpen = imeHeight > 0

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                // The Scaffold reserves only the top system bar. The bottom
                // NavigationBar (when shown) applies its own bottom insets, and
                // the Shell consumes the IME inset at its own edge - reserving
                // the bottom here as well would double-apply insets and push the
                // Shell's shortcut rows up into an empty gap above the keyboard.
                contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top),
                bottomBar = {
                    if (!keyboardOpen) {
                        NavigationBar {
                            AliasNullDestination.entries.forEach { destination ->
                                val selected = currentDestination
                                    ?.hierarchy
                                    ?.any { it.route == destination.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navController.navigateToTopLevel(destination) },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = null,
                                        )
                                    },
                                    label = {
                                        Text(text = stringResource(destination.labelRes))
                                    },
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                AliasNullNavHost(
                    navController = navController,
                    contentPadding = innerPadding,
                )
            }

            // Invisible IME sensor. It has zero width, so it never intercepts
            // input or affects layout, and it mirrors the current IME inset into
            // [imeHeight]. onSizeChanged must sit OUTSIDE imePadding: as the inner
            // modifier it would only measure the empty content box (always 0);
            // measured here it sees the node's full size including the IME padding,
            // so it actually tracks the keyboard opening and closing.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onSizeChanged { imeHeight = it.height }
                    .imePadding(),
            )
        }
    }
}
