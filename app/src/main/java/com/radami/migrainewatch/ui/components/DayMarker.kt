package com.radami.migrainewatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val DROP_DIRECTION = "drop"

/**
 * Corner rounding shared by every severity-coloured surface. It is a percentage rather than
 * a fixed dp so an 8 dp legend swatch and a 45 dp day cell read as the same shape.
 */
val SeverityShape = RoundedCornerShape(percent = 28)

private val TREND_ICON_SIZE = 14.dp
private val TODAY_BORDER_WIDTH = 2.dp

/**
 * A single day in the calendar grid or the Today week strip: the day number, with a pressure
 * trend arrow underneath when that day is part of an event.
 *
 * @param severityColor fill for a logged day; null leaves the marker unfilled.
 * @param eventDirection "drop" or "rise", or null when no pressure event touches the day.
 */
@Composable
fun DayMarker(
    day: Int,
    severityColor: Color?,
    eventDirection: String?,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    Column(
        // Clip and fill first so the click ripple follows the marker's shape.
        modifier = modifier
            .clip(SeverityShape)
            .background(severityColor ?: Color.Transparent)
            .then(
                if (isToday) Modifier.border(TODAY_BORDER_WIDTH, MaterialTheme.colorScheme.primary, SeverityShape)
                else Modifier
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (severityColor != null) Color.White else MaterialTheme.colorScheme.onSurface
        )

        // The trend slot is always laid out, even when there is no event, so the day numbers
        // sit at the same height in every marker instead of shifting up on event days.
        Box(
            modifier = Modifier.height(TREND_ICON_SIZE),
            contentAlignment = Alignment.Center
        ) {
            if (eventDirection != null) {
                // White on filled severity markers for contrast, tertiary on plain days.
                val trendTint =
                    if (severityColor != null) Color.White else MaterialTheme.colorScheme.tertiary
                Icon(
                    imageVector = if (eventDirection == DROP_DIRECTION) Icons.AutoMirrored.Filled.TrendingDown
                    else Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = if (eventDirection == DROP_DIRECTION) "Pressure drop" else "Pressure rise",
                    tint = trendTint,
                    modifier = Modifier.size(TREND_ICON_SIZE)
                )
            }
        }
    }
}

/** The colour chip that stands for a severity in legends and summaries. */
@Composable
fun SeveritySwatch(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(SeverityShape)
            .background(color)
    )
}
