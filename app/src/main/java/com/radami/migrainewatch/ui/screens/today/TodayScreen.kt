package com.radami.migrainewatch.ui.screens.today

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
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatHpa
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.SymptomFreeStreak
import com.radami.migrainewatch.ui.components.PressureChart
import com.radami.migrainewatch.ui.theme.ChartMeasuredLight
import com.radami.migrainewatch.ui.theme.SeverityAura
import com.radami.migrainewatch.ui.theme.SeverityClear
import com.radami.migrainewatch.ui.theme.SeverityMigraine
import com.radami.migrainewatch.ui.theme.SeverityMild
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun TodayScreen(
    onAlertTap: (List<AlertWindow>) -> Unit,
    onChangeLocation: () -> Unit,
    viewModel: TodayViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val timeFormatter = remember {
        AppDateFormats.FULL_DATE_TIME.withZone(ZoneId.systemDefault())
    }

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
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .semantics { contentDescription = "Location" }
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
            AnimatedVisibility(
                visible = state.alertWindows.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val alerts = state.alertWindows
                if (alerts.isNotEmpty()) {
                    AlertBanner(alerts = alerts, onClick = { onAlertTap(alerts) })
                }
            }
        }

        item {
            PressureCard(state = state)
        }

        item {
            SymptomFreeCard(streak = state.symptomFreeStreak)
        }
    }
}

@Composable
private fun AlertBanner(alerts: List<AlertWindow>, onClick: () -> Unit) {
    val first = alerts.first()
    val timeFormatter = AppDateFormats.SHORT_WEEKDAY_AND_TIME
    val zone = remember { ZoneId.systemDefault() }
    val timeLabel = remember(first) { first.start.atZone(zone).format(timeFormatter) }
    val eventSummary = "${formatHpa(first.delta)} hPa ${first.direction} around $timeLabel"
    val message = if (alerts.size == 1) {
        "Elevated risk · $eventSummary"
    } else {
        "Elevated risk · Multiple events\nNext: $eventSummary"
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
                message,
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
                Text(
                    state.currentPressure?.let { "${it.roundToInt()} hPa" } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
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

private val STREAK_SEVERITY_DOT_SIZE = 10.dp

@Composable
private fun SymptomFreeCard(streak: SymptomFreeStreak?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Symptom-free",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            if (streak == null) {
                NotEnoughDataMessage(
                    hint = "Log a mild, aura or migraine day to start counting."
                )
                return@Column
            }
            // Both halves date-stamp a past day, so they share one reading of "this year"
            // rather than each deciding separately whether a year is worth spelling out.
            val currentYear = remember { LocalDate.now().year }

            CurrentStreak(streak = streak, currentYear = currentYear)
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            LongestStreak(longest = streak.longest, currentYear = currentYear)
        }
    }
}

@Composable
private fun CurrentStreak(streak: SymptomFreeStreak, currentYear: Int) {
    val lastEventLabel = remember(streak.lastEvent, currentYear) {
        val date = streak.lastEvent.date.format(dateFormatterFor(streak.lastEvent.date, currentYear))
        "Last: ${streak.lastEvent.severity.label} on $date"
    }

    // The count and the date read as one sentence, so they are announced as one node. Left
    // unmerged, the figure, its unit and the date below it are each their own node and the
    // date ends up spoken twice.
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "${dayCount(streak.currentDays)} symptom-free. $lastEventLabel"
        }
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                streak.currentDays.toString(),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                dayUnit(streak.currentDays),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The swatch ties the date back to the colour the day already wears in the calendar.
            Box(
                modifier = Modifier
                    .size(STREAK_SEVERITY_DOT_SIZE)
                    .clip(CircleShape)
                    .background(streak.lastEvent.severity.toColor())
            )
            Spacer(Modifier.width(6.dp))
            Text(
                lastEventLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Spells the year out only once this year is the wrong thing to assume — a long streak dates its
 * last event years back, and "Saturday 15 August" would read as a recent one.
 */
private fun dateFormatterFor(date: LocalDate, currentYear: Int): DateTimeFormatter =
    if (date.year == currentYear) AppDateFormats.DAY_AND_MONTH else AppDateFormats.DAY_MONTH_AND_YEAR

@Composable
private fun LongestStreak(longest: SymptomFreeStreak.Run?, currentYear: Int) {
    // Label and figure sit side by side rather than spread across the row: the log FAB floats
    // over the bottom-right corner of this card, so anything right-aligned here disappears
    // under it on a narrow screen.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Longest streak",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        Spacer(Modifier.width(8.dp))
        // A second event is what creates the first gap to measure, so until then there is
        // genuinely nothing to report rather than a streak of zero.
        Text(
            longest?.let { dayCount(it.days) } ?: NOT_ENOUGH_DATA,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }

    // A run of zero days spans no days at all, so there is no range to name.
    if (longest == null || longest.days == 0L) return

    val rangeLabel = remember(longest, currentYear) { longest.rangeLabel(currentYear) }
    Spacer(Modifier.height(2.dp))
    Text(
        rangeLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

/**
 * "12 May – 3 Jun", carrying the year on the same terms as [dateFormatterFor] — a record set
 * years ago would otherwise read as a recent one.
 */
private fun SymptomFreeStreak.Run.rangeLabel(currentYear: Int): String {
    val formatter = if (to.year == currentYear) {
        AppDateFormats.SHORT_DAY_AND_MONTH
    } else {
        AppDateFormats.SHORT_DATE_AND_YEAR
    }
    return "${from.format(formatter)} – ${to.format(formatter)}"
}

@Composable
private fun NotEnoughDataMessage(hint: String) {
    Text(
        NOT_ENOUGH_DATA,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(Modifier.height(4.dp))
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

private const val NOT_ENOUGH_DATA = "Not enough data"

private fun dayCount(days: Long): String = "$days ${dayUnit(days)}"

private fun dayUnit(days: Long): String = if (days == 1L) "day" else "days"

private fun Severity.toColor(): Color = when (this) {
    Severity.CLEAR -> SeverityClear
    Severity.MILD -> SeverityMild
    Severity.AURA -> SeverityAura
    Severity.MIGRAINE -> SeverityMigraine
}
