package com.example.migrainetracker.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.model.Severity
import com.example.migrainetracker.data.model.SymptomEntry
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.PressureRepository
import com.example.migrainetracker.data.repository.SymptomRepository
import com.example.migrainetracker.domain.AlertDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val entriesInMonth: Map<LocalDate, SymptomEntry> = emptyMap(),
    // Days touched by a pressure event, mapped to the dominant direction ("drop"/"rise")
    // on that day — the direction whose events overlap the day the longest.
    val pressureEventDays: Map<LocalDate, String> = emptyMap(),
    // Entry counts per severity for the statistics card. All periods are relative to
    // today, not to the month being browsed in the calendar.
    val monthLogCounts: Map<Severity, Int> = emptyMap(),
    val lastMonthLogCounts: Map<Severity, Int> = emptyMap(),
    val last12MonthsLogCounts: Map<Severity, Int> = emptyMap(),
    val selectedEntry: SymptomEntry? = null,
    val showBottomSheet: Boolean = false
)

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val symptomRepository: SymptomRepository,
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    private val currentMonth = MutableStateFlow(YearMonth.now())

    init {
        observeData()
    }

    fun previousMonth() {
        currentMonth.value = currentMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        currentMonth.value = currentMonth.value.plusMonths(1)
    }

    fun showDayDetail(entry: SymptomEntry) {
        _uiState.value = _uiState.value.copy(selectedEntry = entry, showBottomSheet = true)
    }

    fun dismissBottomSheet() {
        _uiState.value = _uiState.value.copy(showBottomSheet = false, selectedEntry = null)
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(currentMonth, userPreferences.settings) { month, settings ->
                Pair(month, settings)
            }.collectLatest { (month, settings) ->
                val from = month.atDay(1)
                val to = month.atEndOfMonth()
                val fromInstant = from.atStartOfDay(ZoneId.systemDefault()).toInstant()
                val toInstant = to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()

                val today = LocalDate.now()
                val statsFrom = today.minusMonths(12)

                combine(
                    symptomRepository.getEntriesInRange(from, to),
                    pressureRepository.getReadingsInRange(fromInstant, toInstant),
                    symptomRepository.getEntriesInRange(statsFrom, today)
                ) { entries, readings, statsEntries -> Triple(entries, readings, statsEntries) }
                    .collectLatest { (entries, readings, statsEntries) ->
                        val entriesMap = entries.associateBy { it.date }

                        val eventDays = withContext(Dispatchers.Default) {
                            computeEventDays(readings, settings.alertThresholdHpa, ZoneId.systemDefault())
                        }

                        val thisMonth = YearMonth.now()
                        val lastMonth = thisMonth.minusMonths(1)
                        val monthLogCounts = statsEntries
                            .filter { YearMonth.from(it.date) == thisMonth }
                            .groupingBy { it.severity }.eachCount()
                        val lastMonthLogCounts = statsEntries
                            .filter { YearMonth.from(it.date) == lastMonth }
                            .groupingBy { it.severity }.eachCount()
                        // The fetched range is exactly the rolling 12-month window.
                        val last12MonthsLogCounts = statsEntries.groupingBy { it.severity }.eachCount()

                        _uiState.value = CalendarUiState(
                            currentMonth = month,
                            entriesInMonth = entriesMap,
                            pressureEventDays = eventDays,
                            monthLogCounts = monthLogCounts,
                            lastMonthLogCounts = lastMonthLogCounts,
                            last12MonthsLogCounts = last12MonthsLogCounts,
                            showBottomSheet = _uiState.value.showBottomSheet,
                            selectedEntry = _uiState.value.selectedEntry
                        )
                    }
            }
        }
    }

    private fun computeEventDays(
        readings: List<PressureReading>,
        threshold: Float,
        zone: ZoneId
    ): Map<LocalDate, String> {
        val alerts = AlertDetector.detect(readings, threshold)
        // Accumulate how long each direction's events overlap each day; a day touched by
        // several events (e.g. a drop ending and a rise starting) shows only the dominant
        // one, so day cells never need two icons.
        val overlaps = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        alerts.forEach { alert ->
            var day = alert.start.atZone(zone).toLocalDate()
            val lastDay = alert.end.atZone(zone).toLocalDate()
            while (!day.isAfter(lastDay)) {
                val dayStart = day.atStartOfDay(zone).toInstant()
                val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
                val overlapSeconds =
                    minOf(alert.end, dayEnd).epochSecond - maxOf(alert.start, dayStart).epochSecond
                val perDirection = overlaps.getOrPut(day) { mutableMapOf() }
                perDirection.merge(alert.direction, overlapSeconds, Long::plus)
                day = day.plusDays(1)
            }
        }
        return overlaps.mapValues { (_, directions) -> directions.maxBy { it.value }.key }
    }
}
