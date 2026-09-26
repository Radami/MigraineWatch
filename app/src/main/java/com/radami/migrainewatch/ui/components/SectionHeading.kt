package com.radami.migrainewatch.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight

/**
 * The name at the top of a section — "7-day outlook", "Alerts", "Statistics", "Notifications".
 * Shared so headings look alike across screens. Colour is fixed (not inherited) so it reads
 * the same grey on a card or on Settings' bare background; caller controls spacing above it.
 */
@Composable
fun SectionHeading(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
