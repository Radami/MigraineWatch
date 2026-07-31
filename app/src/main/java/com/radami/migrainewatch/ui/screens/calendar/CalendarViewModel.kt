package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertDetector
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

    fun deleteEntry(entry: SymptomEntry) {
        viewModelScope.launch {
            runCatching { symptomRepository.delete(entry.date) }
                .onSuccess { dismissBottomSheet() }
                // The calendar keeps showing the entry, so a failure is visible rather than
                // silent; the sheet stays open for the user to try again.
                .onFailure { Log.e("CalendarViewModel", "Failed to delete entry for ${entry.date}", it) }
        }
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
                            val alerts = AlertDetector.detect(readings, settings.alertThresholdHpa)
                            AlertDetector.eventDaysByDirection(alerts, ZoneId.systemDefault())
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

}
