package com.radami.migrainewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AlertColorsLight = listOf(Alert1Light, Alert2Light, Alert3Light)

private val AlertColorsDark = listOf(Alert1Dark, Alert2Dark, Alert3Dark)

/**
 * How many alerts can be told apart by colour, and so how many of them the Pressure screen
 * lists and shades before it starts saying how many it is holding back.
 *
 * The screens take the bound from the palette they are already holding rather than from here;
 * this exists so a test can state the expected count without writing the number down twice.
 */
val ALERT_COLOR_COUNT: Int = AlertColorsLight.size

/**
 * Fixed colours for a pressure alert, independent of dynamic (wallpaper) theming, so a band
 * shaded on the chart and the row describing it below can be recognised as the same event.
 *
 * Indexed by the alert's position in the list. Callers take no more alerts than there are
 * colours rather than wrapping round: two events in one colour would defeat the only thing
 * the colours are for. See [ALERT_COLOR_COUNT].
 */
@Composable
fun alertColorPalette(): List<Color> =
    if (isSystemInDarkTheme()) AlertColorsDark else AlertColorsLight
