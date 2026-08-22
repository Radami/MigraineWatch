package com.radami.migrainewatch.ui.screens.pressure

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.ChartWindow
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatHpa
import com.radami.migrainewatch.format.label
import com.radami.migrainewatch.ui.components.PressureChart
import com.radami.migrainewatch.ui.theme.alertColorPalette
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Whether the chart's selected range reaches the event a row describes. */
private enum class ChartVisibility { InView, OutOfView }

/** Text that is present but not the point: an empty card, or a row the chart cannot show. */
private const val MUTED_ALPHA = 0.5f

private const val SECONDARY_TEXT_ALPHA = 0.6f

@Composable
fun PressureScreen(
    onChangeLocation: () -> Unit,
    viewModel: PressureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormatter = remember { AppDateFormats.FULL_DATE_TIME.withZone(ZoneId.systemDefault()) }

    // The chart and the alert list are two views of one window, so both are built from the
    // same one: what the chart shades is exactly what the list does not have to explain.
    // Snapping makes it a stable value across recompositions within the same step.
    val chartWindow = ChartWindow.around(Instant.now(), state.selectedRange.step)

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // When everything fits on screen there is nothing to scroll to; disable dragging
        // (and its overscroll stretch) so the screen feels as static as the Today screen.
        userScrollEnabled = listState.canScrollForward || listState.canScrollBackward
    ) {
        item {
            Column {
                if (state.locationName.isNotEmpty()) {
                    AssistChip(
                        onClick = onChangeLocation,
                        label = { Text(state.locationName) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.primary,
                            leadingIconContentColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                Text(
                    "Updated ${state.lastUpdated?.let { timeFormatter.format(it) } ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_TEXT_ALPHA)
                )
            }
        }

        item {
            PressureHistoryCard(
                state = state,
                window = chartWindow,
                onSelectRange = viewModel::selectRange
            )
        }

        item {
            AlertsCard(state = state, window = chartWindow)
        }
    }
}

@Composable
private fun PressureHistoryCard(
    state: PressureUiState,
    window: ChartWindow,
    onSelectRange: (TimeRange) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // The reading sits beside the heading, as it does on the Today screen: the chart
            // below is about where pressure is going, and this is where it is now.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Pressure",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        // What the chart shows for each chip: 8 points from −3 to +4 steps
                        // around now, at 3 h / 6 h / 24 h per step.
                        when (state.selectedRange) {
                            TimeRange.Hours24 -> "9 hrs back · 12 hrs ahead"
                            TimeRange.Hours48 -> "18 hrs back · 24 hrs ahead"
                            TimeRange.Days7 -> "3 days back · 4 days ahead"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_TEXT_ALPHA)
                    )
                }
                Text(
                    state.currentPressure?.let { "${it.roundToInt()} hPa" } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.selectedRange == range,
                        onClick = { onSelectRange(range) },
                        label = { Text(range.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier.semantics {
                            contentDescription = "Time range ${range.label}"
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (state.readings.isNotEmpty()) {
                PressureChart(
                    readings = state.readings,
                    window = window,
                    alerts = state.alertWindows,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AlertsCard(state: PressureUiState, window: ChartWindow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Alerts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Pressure events above ${formatThreshold(state.alertThresholdHpa)} hPa, " +
                    "shaded on the chart above",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_TEXT_ALPHA)
            )
            Spacer(Modifier.height(12.dp))

            if (state.alertWindows.isEmpty()) {
                Text(
                    // Both bounds, because the card holds neither only-past nor only-future
                    // events: detection reaches forward to the end of the forecast and back
                    // far enough to keep an event that has just finished.
                    "No pressure events above " +
                        "${formatThreshold(state.alertThresholdHpa)} hPa " +
                        "in the last ${PressureAlertUseCase.RELEVANCE_HOURS} hours " +
                        "or the next ${PressureAlertUseCase.FORECAST_DAYS} days",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA)
                )
                return@Column
            }

            // Colours follow the alert's position in the list, which is how the chart colours
            // its bands too, so a row and its shading can be told apart from the pair below it.
            // The palette bounds the list for that reason: a fourth row would have to reuse a
            // colour, and two events wearing one colour is worse than a fourth row unlisted.
            val palette = alertColorPalette()
            val shown = state.alertWindows.take(palette.size)

            Column {
                shown.forEachIndexed { index, alert ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    }
                    AlertRow(
                        alert = alert,
                        color = palette[index],
                        visibility = if (window.covers(alert)) ChartVisibility.InView
                        else ChartVisibility.OutOfView
                    )
                }
            }

            // A truncated list must not read as the whole picture: a stretch of weather with
            // five events in it would otherwise look like one with three.
            val hidden = state.alertWindows.size - shown.size
            if (hidden > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (hidden == 1) "1 more event not shown"
                    else "$hidden more events not shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA)
                )
            }
        }
    }
}

private fun formatThreshold(threshold: Float): String =
    if (threshold == threshold.toInt().toFloat()) threshold.toInt().toString()
    else formatHpa(threshold)

@Composable
private fun AlertRow(alert: AlertWindow, color: Color, visibility: ChartVisibility) {
    val formatter = remember { AppDateFormats.DAY_AND_TIME.withZone(ZoneId.systemDefault()) }
    val isDrop = alert.direction == PressureDirection.DROP
    val directionLabel = alert.direction.label

    // An event beyond the selected range is faded and says so: a row with no band on the
    // chart above would otherwise read as shading that failed to draw.
    val contentAlpha = when (visibility) {
        ChartVisibility.InView -> 1f
        ChartVisibility.OutOfView -> MUTED_ALPHA
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isDrop) Icons.AutoMirrored.Filled.TrendingDown
            else Icons.AutoMirrored.Filled.TrendingUp,
            contentDescription = directionLabel,
            tint = color.copy(alpha = contentAlpha)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${formatHpa(alert.delta)} hPa $directionLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                "${formatter.format(alert.start)} → ${formatter.format(alert.end)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
                    .copy(alpha = SECONDARY_TEXT_ALPHA * contentAlpha)
            )
            // A line of its own, short and left-aligned: at the end of the row or of the
            // times it would run under the log-symptoms button floating over this corner.
            if (visibility == ChartVisibility.OutOfView) {
                Text(
                    "not in view",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA),
                    modifier = Modifier.semantics {
                        contentDescription = "Outside the chart's selected range"
                    }
                )
            }
        }
    }
}
