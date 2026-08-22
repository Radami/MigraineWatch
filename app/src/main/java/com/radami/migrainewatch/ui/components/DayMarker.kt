package com.radami.migrainewatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Corner rounding shared by every severity-coloured surface. It is a percentage rather than
 * a fixed dp so a 12 dp legend swatch and a 45 dp day cell read as the same shape.
 */
val SeverityShape = RoundedCornerShape(percent = 28)

/**
 * The silhouette of a high-risk day. Shape, not colour, carries the risk so that the severity
 * palette is left free to mean only what it has always meant.
 */
val HighRiskShape = CircleShape

private val TODAY_BORDER_WIDTH = 2.dp

/**
 * Thinner than today's border on purpose: both rings can land on the same day, and today is
 * the cell users hunt for, so it has to stay the heavier of the two. Public because the legend
 * draws the same ring, and the two have to stay identical for the legend to mean anything.
 */
val HIGH_RISK_BORDER_WIDTH = 1.5.dp

/** Whether a pressure event crossing the alert threshold touches the day. */
enum class DayRisk { Normal, High }

/**
 * A single day in the calendar grid: the day number centred in a shape that says whether the
 * day is high risk, filled with the severity colour once something has been logged.
 *
 * @param severityColor fill for a logged day; null leaves the marker unfilled.
 * @param risk whether a pressure event crossing the alert threshold touches the day.
 */
@Composable
fun DayMarker(
    day: Int,
    severityColor: Color?,
    risk: DayRisk,
    isToday: Boolean,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null
) {
    val markerShape = if (risk == DayRisk.High) HighRiskShape else SeverityShape

    Box(
        // Clip and fill first so the click ripple follows the marker's shape.
        modifier = modifier
            .clip(markerShape)
            .background(severityColor ?: Color.Transparent)
            // A circle is always outlined, filled or not: the ring is what makes the silhouette
            // read as deliberate rather than as a differently-shaped severity chip. Exactly one
            // ring is ever drawn, and today outranks risk for the edge — the shape has already
            // said "high risk" by then.
            .then(
                when {
                    isToday ->
                        Modifier.border(TODAY_BORDER_WIDTH, MaterialTheme.colorScheme.primary, markerShape)

                    risk == DayRisk.High ->
                        Modifier.border(HIGH_RISK_BORDER_WIDTH, MaterialTheme.colorScheme.outline, markerShape)

                    else -> Modifier
                }
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (severityColor != null) Color.White else MaterialTheme.colorScheme.onSurface
        )
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
