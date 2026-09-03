package app.aliasnull.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = AliasNullColors.LightPrimary,
    onPrimary = AliasNullColors.LightOnPrimary,
    primaryContainer = AliasNullColors.LightPrimaryContainer,
    onPrimaryContainer = AliasNullColors.LightOnPrimaryContainer,
    secondary = AliasNullColors.LightSecondary,
    onSecondary = AliasNullColors.LightOnSecondary,
    background = AliasNullColors.LightBackground,
    onBackground = AliasNullColors.LightOnBackground,
    surface = AliasNullColors.LightSurface,
    onSurface = AliasNullColors.LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = AliasNullColors.DarkPrimary,
    onPrimary = AliasNullColors.DarkOnPrimary,
    primaryContainer = AliasNullColors.DarkPrimaryContainer,
    onPrimaryContainer = AliasNullColors.DarkOnPrimaryContainer,
    secondary = AliasNullColors.DarkSecondary,
    onSecondary = AliasNullColors.DarkOnSecondary,
    background = AliasNullColors.DarkBackground,
    onBackground = AliasNullColors.DarkOnBackground,
    surface = AliasNullColors.DarkSurface,
    onSurface = AliasNullColors.DarkOnSurface,
)

@Composable
fun AliasNullTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AliasNullTypography,
        content = content,
    )
}
