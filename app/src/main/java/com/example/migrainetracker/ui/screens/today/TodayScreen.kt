package com.example.migrainetracker.ui.screens.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.example.migrainetracker.data.model.Severity
import com.example.migrainetracker.data.model.SymptomEntry
import com.example.migrainetracker.domain.AlertWindow
import com.example.migrainetracker.ui.components.PressureChart
import com.example.migrainetracker.ui.theme.ChartMeasuredLight
import com.example.migrainetracker.ui.theme.SeverityAura
import com.example.migrainetracker.ui.theme.SeverityClear
import com.example.migrainetracker.ui.theme.SeverityMigraine
import com.example.migrainetracker.ui.theme.SeverityMild
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    onPressureTap: () -> Unit,
    onLogTap: () -> Unit,
    onChangeLocation: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy") }

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
                    today.format(dateFormatter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        item {
            AnimatedVisibility(
                visible = state.alertWindows.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                state.alertWindows.firstOrNull()?.let { alert ->
                    AlertBanner(alert = alert, onClick = onPressureTap)
                }
            }
        }

        item {
            PressureCard(state = state)
        }

        item {
            SymptomLogCard(
                weekEntries = state.weekEntries,
                pressureDropDays = state.pressureDropDays,
                today = today,
                onLogTap = onLogTap
            )
        }
    }
}

@Composable
private fun AlertBanner(alert: AlertWindow, onClick: () -> Unit) {
    val timeFormatter = remember { DateTimeFormatter.ofPattern("EEE HH:mm") }
    val zone = remember { ZoneId.systemDefault() }
    val timeLabel = remember(alert) {
        alert.start.atZone(zone).format(timeFormatter)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Pressure alert banner" }
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "Elevated risk · ${String.format("%.1f", alert.delta)} hPa ${alert.direction} around $timeLabel",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "View details",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun PressureCard(state: TodayUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Barometric pressure",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "3 days back · 4 days ahead",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        state.currentPressure?.let { "${it.toInt()} hPa" } ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.maxForecastDrop > 0f) {
                        Text(
                            "↘ ${String.format("%.1f", state.maxForecastDrop)} hPa / 24h",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.historical.isNotEmpty() || state.forecast.isNotEmpty()) {
                PressureChart(
                    historical = state.historical,
                    forecast = state.forecast,
                    modifier = Modifier.fillMaxWidth(),
                    stepHours = 24
                )
            } else if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Loading pressure data…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Unable to load — check your connection",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SymptomLogCard(
    weekEntries: Map<LocalDate, SymptomEntry?>,
    pressureDropDays: Set<LocalDate>,
    today: LocalDate,
    onLogTap: () -> Unit
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Symptom log",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    today.format(monthFormatter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(Modifier.height(12.dp))
            WeekStrip(
                weekEntries = weekEntries,
                pressureDropDays = pressureDropDays,
                today = today
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onLogTap,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Log today's symptoms")
            }
        }
    }
}

@Composable
private fun WeekStrip(
    weekEntries: Map<LocalDate, SymptomEntry?>,
    pressureDropDays: Set<LocalDate>,
    today: LocalDate
) {
    val dayFormatter = remember { DateTimeFormatter.ofPattern("E") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        weekEntries.entries.sortedBy { it.key }.forEach { (date, entry) ->
            val isToday = date == today
            val bgColor = entry?.severity?.toColor() ?: Color.Transparent
            val hasDrop = pressureDropDays.contains(date)
            val dayLabel = date.format(dayFormatter).take(1)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics {
                    contentDescription = "$dayLabel ${date}, ${entry?.severity?.name ?: "No entry"}"
                }
            ) {
                Text(
                    dayLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (bgColor != Color.Transparent) bgColor else MaterialTheme.colorScheme.surfaceVariant)
                        .then(
                            if (isToday) Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (entry != null) Color.White else MaterialTheme.colorScheme.onSurface
                    )
                }
                if (hasDrop) {
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiary)
                            .semantics { contentDescription = "Pressure drop" }
                    )
                }
            }
        }
    }
}

private fun Severity.toColor(): Color = when (this) {
    Severity.CLEAR -> SeverityClear
    Severity.MILD -> SeverityMild
    Severity.AURA -> SeverityAura
    Severity.MIGRAINE -> SeverityMigraine
}
