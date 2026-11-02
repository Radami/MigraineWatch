package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.ViewModel
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.domain.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaYearMonth
import kotlinx.datetime.toJavaZoneId
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlin.time.Clock

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(LocationData().zone.toJavaZoneId()),
    /** Today at the location, which the grid marks and the statistics count back from. */
    val today: LocalDate = LocalDate.now(LocationData().zone.toJavaZoneId()),
    val entriesInMonth: Map<LocalDate, SymptomEntry> = emptyMap(),
    // Days touched by a pressure event that crossed the user's alert threshold. The calendar
    // marks these as high risk; which way the pressure moved is not shown.
    val highRiskDays: Set<LocalDate> = emptySet(),
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

    /**
     * Months back (negative) or ahead of the location's current month, rather than a month
     * itself: which month is current depends on the location's zone, unknown until settings
     * load.
     */
    private val monthOffset = MutableStateFlow(0L)

    init {
        observeData()
    }

    fun previousMonth() {
        monthOffset.value -= 1
    }

    fun nextMonth() {
        monthOffset.value += 1
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
            combine(monthOffset, userPreferences.settings) { offset, settings ->
                Pair(offset, settings)
            }.collectLatest { (offset, settings) ->
                // Days, months and the readings' bounds all counted at the location.
                val period = calendarPeriod(offset, settings.location.zone, Clock.System.now())

                combine(
                    symptomRepository.getEntriesInRange(period.month.firstDay, period.month.lastDay),
                    pressureRepository.getReadingsInRange(period.readingsFrom, period.readingsUntil),
                    symptomRepository.getEntriesInRange(period.statsFrom, period.today)
                ) { entries, readings, statsEntries -> Triple(entries, readings, statsEntries) }
                    .collectLatest { (entries, readings, statsEntries) ->
                        // Event detection walks the whole month's readings, so it runs off the main thread.
                        val state = withContext(Dispatchers.Default) {
                            calendarState(period, entries, readings, statsEntries, settings.alertThresholdHpa)
                        }

                        _uiState.value = _uiState.value.showing(state)
                    }
            }
        }
    }

}

/**
 * The screen still reads `java.time` types until it moves to the shared UI. The bottom sheet is
 * the screen's own, so it carries over every data emission.
 */
private fun CalendarUiState.showing(state: CalendarState) = copy(
    currentMonth = state.month.toJavaYearMonth(),
    today = state.today.toJavaLocalDate(),
    entriesInMonth = state.entriesInMonth.mapKeys { (date, _) -> date.toJavaLocalDate() },
    highRiskDays = state.highRiskDays.mapTo(HashSet()) { it.toJavaLocalDate() },
    monthLogCounts = state.monthLogCounts,
    lastMonthLogCounts = state.lastMonthLogCounts,
    last12MonthsLogCounts = state.last12MonthsLogCounts
)
