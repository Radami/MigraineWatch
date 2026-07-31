package com.radami.migrainewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed colours for a pressure alert, independent of dynamic (wallpaper) theming.
 * [base] is used for chart shading and legend swatches; [container] and [onContainer]
 * for the alert banner cards.
 */
data class AlertColors(
    val base: Color,
    val container: Color,
    val onContainer: Color,
)

private val AlertColorsLight = listOf(
    AlertColors(base = Alert1Light, container = Alert1ContainerLight, onContainer = Alert1ContainerDark),
    AlertColors(base = Alert2Light, container = Alert2ContainerLight, onContainer = Alert2ContainerDark),
    AlertColors(base = Alert3Light, container = Alert3ContainerLight, onContainer = Alert3ContainerDark),
)

private val AlertColorsDark = listOf(
    AlertColors(base = Alert1Dark, container = Alert1ContainerDark, onContainer = Alert1ContainerLight),
    AlertColors(base = Alert2Dark, container = Alert2ContainerDark, onContainer = Alert2ContainerLight),
    AlertColors(base = Alert3Dark, container = Alert3ContainerDark, onContainer = Alert3ContainerLight),
)

/** Per-alert palette, indexed by alert position; wrap with `% size` for more alerts. */
@Composable
fun alertColorPalette(): List<AlertColors> =
    if (isSystemInDarkTheme()) AlertColorsDark else AlertColorsLight
