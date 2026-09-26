package com.radami.migrainewatch.ui.screens.pressure

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import com.radami.migrainewatch.ui.theme.Motion
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.format.formatAlertHeadline
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatHpa
import com.radami.migrainewatch.format.label
import com.radami.migrainewatch.ui.components.SectionHeading
import com.radami.migrainewatch.ui.components.PressureChart
import com.radami.migrainewatch.ui.theme.alertColorPalette
import com.radami.migrainewatch.ui.theme.MUTED_ALPHA
import com.radami.migrainewatch.ui.theme.SECONDARY_ALPHA
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Whether the chart's selected range reaches the event a row describes. */
private enum class ChartVisibility { InView, OutOfView }

/** Everything the Alerts card lists, held together so it animates as one crossfade, not per-row. */
private data class AlertListing(val rows: List<AlertWindow>, val hidden: Int)

@Composable
fun PressureScreen(
    onChangeLocation: () -> Unit,
    viewModel: PressureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormatter = remember { AppDateFormats.FULL_DATE_TIME.withZone(ZoneId.systemDefault()) }

    // Chart and alert list share one window so what the chart shades is what the list doesn't
    // have to explain. Snapping keeps it stable across recompositions in the same step.
    val chartWindow = ChartWindow.around(Instant.now(), state.selectedRange.step)

    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        // Disable dragging/overscroll when content fits, so it feels as static as the Today screen.
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
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
            // Current reading beside the heading, as on the Today screen; the chart is the trend.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeading("Pressure")
                    Text(
                        // Keyed off the step, not the chip, so chips sharing a step describe the same span.
                        when (state.selectedRange.step) {
                            ChartStep.ThreeHours -> "9 hrs back · 12 hrs ahead"
                            ChartStep.SixHours -> "18 hrs back · 24 hrs ahead"
                            ChartStep.OneDay -> "3 days back · 4 days ahead"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
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

            PressureChart(
                readings = state.readings,
                window = window,
                requestedRendering = state.selectedRange.rendering,
                alerts = state.alertWindows,
                modifier = Modifier.fillMaxWidth()
            ) {
                EmptyChartMessage(state)
            }
        }
    }
}

/**
 * Stands in for the chart when readings don't reach the selected range, so blank space doesn't
 * read as a render failure. Asks the fetch state to explain why, like the Today screen's outlook.
 */
@Composable
private fun EmptyChartMessage(state: PressureUiState) {
    val message = when {
        // Data arrived, just not for this chip's range — unrelated to the network. Range isn't
        // named here since a chip's label isn't its actual span; see the line above this one.
        state.readings.isNotEmpty() -> "No readings in this range"

        else -> when (state.refreshState) {
            // Room delivers writes a few hops after the fetch returns, so success + empty table
            // still means first load in progress — see RefreshState.NoReadings.
            RefreshState.InFlight, RefreshState.Updated -> "Loading pressure readings…"

            // Worded independently of the Today card's equivalent messages.
            RefreshState.NoReadings -> "No pressure readings available for this location"
            RefreshState.Failed -> "Couldn't load pressure readings — check your connection"
            RefreshState.NoLocation -> "Set a location to see pressure"
        }
    }

    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA),
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

@Composable
private fun AlertsCard(state: PressureUiState, window: ChartWindow) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SectionHeading("Alerts")
            Text(
                "Pressure events above ${formatThreshold(state.alertThresholdHpa)} hPa, " +
                    "shaded on the chart above",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
            )
            Spacer(Modifier.height(12.dp))

            // Color follows list position, matching the chart's band colors. Palette size bounds
            // the list since reusing a color would be worse than leaving a row unlisted.
            val palette = alertColorPalette()
            val shown = state.alertWindows.take(palette.size)
            val listing = AlertListing(
                rows = shown,
                // Must say "more" rather than silently truncating.
                hidden = state.alertWindows.size - shown.size
            )

            // Keyed on the listing, not the range: range only changes how a row draws, handled inside the row.
            AnimatedContent(
                targetState = listing,
                transitionSpec = {
                    fadeIn(
                        tween(Motion.CONTENT_ENTER_MILLIS, delayMillis = Motion.CONTENT_EXIT_MILLIS)
                    ).togetherWith(
                        fadeOut(tween(Motion.CONTENT_EXIT_MILLIS))
                    ).using(SizeTransform(clip = false))
                },
                label = "alertListing"
            ) { target ->
                if (target.rows.isEmpty()) {
                    Text(
                        // Both bounds named: detection covers recently-finished events and forecast ones.
                        "No pressure events above " +
                            "${formatThreshold(state.alertThresholdHpa)} hPa " +
                            "in the last ${PressureAlertUseCase.RELEVANCE_HOURS} hours " +
                            "or the next ${PressureAlertUseCase.FORECAST_DAYS} days",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA)
                    )
                    return@AnimatedContent
                }

                Column {
                    target.rows.forEachIndexed { index, alert ->
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

                    if (target.hidden > 0) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (target.hidden == 1) "1 more event not shown"
                            else "${target.hidden} more events not shown",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = MUTED_ALPHA)
                        )
                    }
                }
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

    // Faded and labeled when out of range, so a missing chart band doesn't look like a draw
    // failure. Animated so it settles alongside the chart's own range transition.
    val contentAlpha by animateFloatAsState(
        targetValue = when (visibility) {
            ChartVisibility.InView -> 1f
            ChartVisibility.OutOfView -> MUTED_ALPHA
        },
        animationSpec = tween(Motion.EMPHASIS_MILLIS),
        label = "alertRowAlpha"
    )

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
                formatAlertHeadline(alert.delta, alert.direction),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                "${formatter.format(alert.start)} → ${formatter.format(alert.end)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
                    .copy(alpha = SECONDARY_ALPHA * contentAlpha)
            )
            // Own line, left-aligned: appended to the row it would sit under the log-symptoms FAB.
            // Expands rather than popping in, so the row grows into it smoothly.
            AnimatedVisibility(
                visible = visibility == ChartVisibility.OutOfView,
                enter = fadeIn(tween(Motion.EMPHASIS_MILLIS)) + expandVertically(),
                exit = fadeOut(tween(Motion.CONTENT_EXIT_MILLIS)) + shrinkVertically()
            ) {
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
