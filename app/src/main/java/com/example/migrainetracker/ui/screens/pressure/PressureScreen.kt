package com.example.migrainetracker.ui.screens.pressure

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.ui.components.PressureChart
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PressureScreen(
    onChangeLocation: () -> Unit,
    viewModel: PressureViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm").withZone(ZoneId.systemDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Pressure history",
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
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )

                    Spacer(Modifier.height(16.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeRange.entries.forEach { range ->
                            FilterChip(
                                selected = state.selectedRange == range,
                                onClick = { viewModel.selectRange(range) },
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

                    if (state.historical.isNotEmpty() || state.forecast.isNotEmpty()) {
                        PressureChart(
                            historical = state.historical,
                            forecast = state.forecast,
                            stepHours = when (state.selectedRange) {
                                TimeRange.Hours24 -> 3
                                TimeRange.Hours48 -> 6
                                TimeRange.Days7 -> 24
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        if (state.next12Hours.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Next 12 hours",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))
                        Next12HoursStrip(
                            readings = state.next12Hours,
                            threshold = state.alertThresholdHpa,
                            currentPressure = state.currentPressure
                        )
                    }
                }
            }
        }

        // Removed bottom updated text item
    }
}

@Composable
private fun Next12HoursStrip(
    readings: List<PressureReading>,
    threshold: Float,
    currentPressure: Float?
) {
    val hourFormatter = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(readings) { reading ->
            val delta = currentPressure?.let { reading.pressureMsl - it } ?: 0f
            val isAlert = Math.abs(delta) >= threshold
            Card(
                modifier = Modifier.width(72.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAlert)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        hourFormatter.format(reading.dateTime),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "${reading.pressureMsl.toInt()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        imageVector = if (isAlert) Icons.Default.Warning else Icons.Default.CheckCircle,
                        contentDescription = if (isAlert) "Alert" else "Within threshold",
                        modifier = Modifier.height(16.dp),
                        tint = if (isAlert) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
