package com.pokerpgplayer.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * User-selectable theme preference (Settings screen). SYSTEM follows the
 * device's day/night setting; LIGHT/DARK force an explicit choice.
 *
 * Prototype v0.0.1: this preference lives only in memory for the current
 * app session (see [com.pokerpgplayer.app.viewmodel.SettingsViewModel]).
 * Persisting it (DataStore/SharedPreferences) is Sprint 2 work — not
 * "runtime implementation" in the mkxp-z sense, but deliberately kept out
 * of this prototype to stay tightly scoped to what was asked for.
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val DarkColors = darkColorScheme(
    primary = TealAccent,
    onPrimary = DarkBackground,
    secondary = TealAccentStrong,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnBackground,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    error = DangerRed
)

private val LightColors = lightColorScheme(
    primary = TealAccentStrong,
    onPrimary = LightSurface,
    secondary = TealAccent,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnBackground,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    error = DangerRed
)

@Composable
fun PokeRPGPlayerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (useDarkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PokeRPGPlayerTypography,
        content = content
    )
}
