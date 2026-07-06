package com.example.migrainetracker.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
                        pressureDropDays = state.pressureDropDays,
                        today = today,
                        onPrevMonth = viewModel::previousMonth,
                        onNextMonth = viewModel::nextMonth,
                        onDayTap = { date ->
                            val entry = state.entriesInMonth[date]
                            if (entry != null) viewModel.showDayDetail(entry)
                            else onLogEntry(date)
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    CalendarLegend()
                }
            }
        }

        item {
            SummaryStatsRow(stats = state.stats)
        }

        if (state.entriesInMonth.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Month log",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        val sortedEntries = state.entriesInMonth.values.sortedByDescending { it.date }
                        sortedEntries.forEachIndexed { index, entry ->
                            EntryRow(
                                entry = entry,
                                hasDrop = state.pressureDropDays.contains(entry.date),
                                onClick = { viewModel.showDayDetail(entry) }
                            )
                            if (index < sortedEntries.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
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
                    hasDrop = state.pressureDropDays.contains(entry.date),
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
    pressureDropDays: Set<LocalDate>,
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
                        val hasDrop = pressureDropDays.contains(date)
                        DayCell(
                            day = dayNum,
                            entry = entry,
                            isToday = isToday,
                            hasDrop = hasDrop,
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
    hasDrop: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor: Color = when (entry?.severity) {
        Severity.CLEAR -> SeverityClear
        Severity.MILD, Severity.AURA -> SeverityMild
        Severity.MIGRAINE -> SeverityMigraine
        null -> Color.Transparent
    }
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
            style = MaterialTheme.typography.labelSmall,
            color = if (entry != null) Color.White else MaterialTheme.colorScheme.onSurface
        )
        if (hasDrop) {
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
                    .semantics { contentDescription = "Pressure drop" }
            )
        }
    }
}

@Composable
private fun CalendarLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = SeverityMild, label = "Mild/Aura")
        LegendItem(color = SeverityMigraine, label = "Migraine")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
            Spacer(Modifier.width(4.dp))
            Text("Pressure drop", style = MaterialTheme.typography.labelSmall)
        }
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

@Composable
private fun SummaryStatsRow(stats: MonthStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatCard("Episodes", stats.totalEpisodes.toString())
        StatCard("Severe", stats.severeCount.toString())
        StatCard("After drop", "${stats.afterDropCount}/${stats.totalDropCount}")
    }
}

@Composable
private fun StatCard(label: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EntryRow(
    entry: SymptomEntry,
    hasDrop: Boolean,
    onClick: () -> Unit
) {
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    val severityColor = entry.severity.toColor()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.date.format(dateFormatter),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.width(8.dp))
        SuggestionChip(
            onClick = onClick,
            label = { Text(entry.severity.name.lowercase().replaceFirstChar { it.uppercase() }) },
            colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                containerColor = severityColor.copy(alpha = 0.15f)
            )
        )
        if (hasDrop) {
            Spacer(Modifier.width(4.dp))
            SuggestionChip(
                onClick = onClick,
                label = { Text("↓ Drop") },
                colors = androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            )
        }
        entry.triggers.take(1).forEach { trigger ->
            Spacer(Modifier.width(4.dp))
            SuggestionChip(
                onClick = onClick,
                label = { Text(trigger) }
            )
        }
    }
}

@Composable
private fun DayDetailSheet(
    entry: SymptomEntry,
    hasDrop: Boolean,
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                entry.triggers.forEach { trigger ->
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
