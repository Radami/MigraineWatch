package com.radami.migrainewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * The system's own colours, taken from the wallpaper, where the platform offers them; null
 * where it doesn't, which leaves the app's fixed palette in place.
 */
@Composable
internal expect fun systemColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
fun MigraineWatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemScheme = if (dynamicColor) systemColorScheme(darkTheme) else null
    val colorScheme = when {
        systemScheme != null -> systemScheme
        darkTheme -> BrandDarkColorScheme
        else -> BrandLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
