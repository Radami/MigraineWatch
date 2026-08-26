package com.radami.migrainewatch.ui.screens.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.label
import com.radami.migrainewatch.ui.components.SectionHeading
import com.radami.migrainewatch.ui.components.DayMarker
import com.radami.migrainewatch.ui.components.DayRisk
import com.radami.migrainewatch.ui.components.HighRiskLegendSwatch
import com.radami.migrainewatch.ui.components.LEGEND_SWATCH_SIZE
import com.radami.migrainewatch.ui.components.SeveritySwatch
import com.radami.migrainewatch.ui.theme.DangerRed
import com.radami.migrainewatch.ui.theme.color
import java.time.LocalDate
import java.time.YearMonth

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
                        highRiskDays = state.highRiskDays,
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
                    },
                    onDelete = { viewModel.deleteEntry(entry) }
                )
            }
        }
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    entries: Map<LocalDate, SymptomEntry>,
    highRiskDays: Set<LocalDate>,
    today: LocalDate,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayTap: (LocalDate) -> Unit
) {
    val monthYearFormatter = AppDateFormats.MONTH_AND_YEAR
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
                            risk = if (date in highRiskDays) DayRisk.High else DayRisk.Normal,
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

private val STATS_SWATCH_SIZE = 8.dp

@Composable
private fun DayCell(
    day: Int,
    entry: SymptomEntry?,
    isToday: Boolean,
    risk: DayRisk,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val severityLabel = entry?.severity?.name ?: "No entry"
    // The shape alone carries the risk on screen, so talk-back has to spell it out.
    val riskLabel = if (risk == DayRisk.High) ", high risk" else ""

    DayMarker(
        day = day,
        severityColor = entry?.severity?.color,
        risk = risk,
        isToday = isToday,
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp),
        contentDescription = "Day $day $severityLabel$riskLabel",
        onClick = onClick
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarLegend() {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Declaration order of the enum, which runs clear to worst.
        Severity.entries.forEach { LegendItem(color = it.color, label = it.label) }
        LegendHighRiskItem()
    }
}

/**
 * The one legend entry that stands for a shape rather than a colour, so it is drawn as an
 * unfilled circle in the same outline the unlogged high-risk days use.
 */
@Composable
private fun LegendHighRiskItem() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HighRiskLegendSwatch()
        Spacer(Modifier.width(4.dp))
        Text("High risk", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SeveritySwatch(color = color, size = LEGEND_SWATCH_SIZE)
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
            SectionHeading("Statistics")
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
                            SeveritySwatch(color = severity.color, size = STATS_SWATCH_SIZE)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                severity.label,
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
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormatter = AppDateFormats.DAY_AND_MONTH
    val severityColor = entry.severity.color

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
            SeveritySwatch(color = severityColor, size = LEGEND_SWATCH_SIZE)
            Spacer(Modifier.width(8.dp))
            Text(
                entry.severity.label,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onEdit,
                modifier = Modifier.weight(1f)
            ) {
                Text("Edit entry")
            }
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(
                    containerColor = DangerRed,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "Delete entry" }
            ) {
                Text("Delete entry")
            }
        }
    }
}
