package com.radami.migrainewatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AlertColorsLight = listOf(Alert1Light, Alert2Light, Alert3Light)

private val AlertColorsDark = listOf(Alert1Dark, Alert2Dark, Alert3Dark)

/**
 * How many alerts can be told apart by colour, so the Pressure screen knows when to say it's
 * holding some back. Exists mainly so tests can state the count without hardcoding it twice.
 */
val ALERT_COLOR_COUNT: Int = AlertColorsLight.size

/**
 * Fixed colours for a pressure alert, independent of dynamic theming, so a shaded band and
 * its row read as the same event. Indexed by list position; callers must not take more
 * alerts than there are colours, or two events would share one colour. See [ALERT_COLOR_COUNT].
 */
@Composable
fun alertColorPalette(): List<Color> =
    if (isSystemInDarkTheme()) AlertColorsDark else AlertColorsLight
