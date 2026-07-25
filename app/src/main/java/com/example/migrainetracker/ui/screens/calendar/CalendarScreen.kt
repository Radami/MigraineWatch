package com.example.migrainetracker.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.example.migrainetracker.data.model.Severity
import com.example.migrainetracker.data.model.SymptomEntry
import com.example.migrainetracker.ui.theme.SeverityAura
import com.example.migrainetracker.ui.theme.SeverityClear
import com.example.migrainetracker.ui.theme.SeverityMigraine
import com.example.migrainetracker.ui.theme.SeverityMild
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onLogEntry: (LocalDate) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    MonthCalendar(
                        month = state.currentMonth,
                        entries = state.entriesInMonth,
                        pressureEventDays = state.pressureEventDays,
                        today = today,
                        onPrevMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onDayTap = { date ->
                            val entry = state.entriesInMonth[date]
                            when {
                                entry != null -> viewModel.showDayDetail(entry)
                                // Symptoms can't be logged ahead of time.
                                !date.isAfter(today) -> onLogEntry(date)
                            }
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    CalendarLegend()
                }
            }
        }

        item {
            StatsCard(
                monthLogCounts = state.monthLogCounts,
                lastMonthLogCounts = state.lastMonthLogCounts,
                last12MonthsLogCounts = state.last12MonthsLogCounts
            )
        }
    }

    if (state.showBottomSheet) {
        state.selectedEntry?.let { entry ->
            ModalBottomSheet(
                onDismissRequest = viewModel::dismissBottomSheet,
                sheetState = sheetState
            ) {
                DayDetailSheet(
                    entry = entry,
                    onClose = viewModel::dismissBottomSheet,
                    onEdit = {
                        viewModel.dismissBottomSheet()
                        onLogEntry(entry.date)
                    }
                )
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    entries: Map<LocalDate, SymptomEntry>,
    pressureEventDays: Map<LocalDate, String>,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayTap: (LocalDate) -> Unit
) {
    val monthYearFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    val dayHeaders = listOf("S", "M", "T", "W", "T", "F", "S")

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrevMonth,
                modifier = Modifier.semantics { contentDescription = "Previous month" }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                month.format(monthYearFormatter),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onNextMonth,
                modifier = Modifier.semantics { contentDescription = "Next month" }
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            dayHeaders.forEach { label ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        val firstDay = month.atDay(1)
        // 0=Sun, 1=Mon, ..., 6=Sat
        val startOffset = firstDay.dayOfWeek.value % 7
        val daysInMonth = month.lengthOfMonth()
        val totalCells = startOffset + daysInMonth
        val rows = (totalCells + 6) / 7

        repeat(rows) { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val cellIndex = row * 7 + col
                    val dayNum = cellIndex - startOffset + 1
                    if (dayNum < 1 || dayNum > daysInMonth) {
                        Box(modifier = Modifier.weight(1f))
                    } else {
                        val date = month.atDay(dayNum)
                        val entry = entries[date]
                        val isToday = date == today
                        DayCell(
                            day = dayNum,
                            entry = entry,
                            isToday = isToday,
                            eventDirection = pressureEventDays[date],
                            onClick = { onDayTap(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    entry: SymptomEntry?,
    isToday: Boolean,
    eventDirection: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor: Color = entry?.severity?.toColor() ?: Color.Transparent
    val severityLabel = entry?.severity?.name ?: "No entry"

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (entry != null) bgColor else Color.Transparent)
            .then(
                if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .clickable { onClick() }
            .semantics { contentDescription = "Day $day $severityLabel" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            day.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = if (entry != null) Color.White else MaterialTheme.colorScheme.onSurface
        )
        if (eventDirection != null) {
            // White on filled severity circles for contrast, tertiary on plain days.
            val trendTint = if (entry != null) Color.White else MaterialTheme.colorScheme.tertiary
            Icon(
                imageVector = if (eventDirection == "drop") Icons.AutoMirrored.Filled.TrendingDown
                else Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = if (eventDirection == "drop") "Pressure drop" else "Pressure rise",
                tint = trendTint,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LegendItem(color = SeverityClear, label = "Clear")
        LegendItem(color = SeverityMild, label = "Mild")
        LegendItem(color = SeverityAura, label = "Aura")
        LegendItem(color = SeverityMigraine, label = "Migraine")
        LegendTrendItem(icon = Icons.AutoMirrored.Filled.TrendingDown, label = "Drop")
        LegendTrendItem(icon = Icons.AutoMirrored.Filled.TrendingUp, label = "Rise")
    }
}

@Composable
private fun LegendTrendItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class StatsPeriod(val label: String) {
    ThisMonth("This month"),
    LastMonth("Last month"),
    Last12Months("Last 12 months")
}

@Composable
private fun StatsCard(
    monthLogCounts: Map<Severity, Int>,
    lastMonthLogCounts: Map<Severity, Int>,
    last12MonthsLogCounts: Map<Severity, Int>
) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.ThisMonth) }
    val counts = when (period) {
        StatsPeriod.ThisMonth -> monthLogCounts
        StatsPeriod.LastMonth -> lastMonthLogCounts
        StatsPeriod.Last12Months -> last12MonthsLogCounts
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatsPeriod.entries.forEach { p ->
                    FilterChip(
                        selected = period == p,
                        onClick = { period = p },
                        label = { Text(p.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Severity.entries.forEach { severity ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            (counts[severity] ?: 0).toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(severity.toColor())
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                severity.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayDetailSheet(
    entry: SymptomEntry,
    onClose: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE d MMMM") }
    val severityColor = entry.severity.toColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                entry.date.format(dateFormatter),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics { contentDescription = "Close" }
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(severityColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.severity.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(12.dp))
        // FlowRow, not Row: chips carry user-entered labels of any length and must wrap
        // onto further lines instead of running off the edge of the sheet.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            entry.durationBucket?.let { dur ->
                AssistChip(onClick = {}, label = { Text(dur) })
            }
            entry.reliefPercent?.let { relief ->
                AssistChip(onClick = {}, label = { Text("$relief% relief") })
            }
        }
        if (entry.triggers.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Triggers",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                entry.triggers.inTriggerOrder().forEach { trigger ->
                    AssistChip(onClick = {}, label = { Text(trigger) })
                }
            }
        }
        entry.medication?.takeIf { it.isNotBlank() }?.let { med ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Medication",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(med, style = MaterialTheme.typography.bodyMedium)
        }
        entry.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Note",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.Button(
            onClick = onEdit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Edit entry")
        }
    }
}

private fun Severity.toColor(): Color = when (this) {
    Severity.CLEAR -> SeverityClear
    Severity.MILD -> SeverityMild
    Severity.AURA -> SeverityAura
    Severity.MIGRAINE -> SeverityMigraine
}
