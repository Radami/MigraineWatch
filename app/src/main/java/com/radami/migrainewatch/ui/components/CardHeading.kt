package com.radami.migrainewatch.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * The name at the top of a card — "7-day outlook", "Alerts", "Statistics".
 *
 * One composable rather than the same two lines repeated per screen: five cards across three
 * screens have to look alike for the app to read as one thing, and a style spelled out at each
 * site is a style that drifts the first time one of them is edited on its own.
 *
 * The colour is named rather than inherited, and that is the point: a card hands down
 * `onSurfaceVariant` while the bare background of Settings hands down the darker `onSurface`,
 * so a heading that took whatever it was given would come out a different grey depending on
 * what it happened to sit on.
 */
@Composable
fun CardHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
