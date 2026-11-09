package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.coroutines.catchingFailures
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.Severity
import com.radami.migrainewatch.logging.Logger
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.time.clockTicks
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.todayIn
import kotlinx.datetime.yearMonth

data class CalendarUiState(
    val currentMonth: YearMonth = Clock.System.todayIn(LocationData().zone).yearMonth,
    /** Today at the location, which the grid marks and the statistics count back from. */
    val today: LocalDate = Clock.System.todayIn(LocationData().zone),
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

class CalendarViewModel(
    private val symptomRepository: SymptomRepository,
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences,
    /**
     * The time now and at every tick after, from [clockTicks] outside tests. The screen's window
     * and "now" move with it, so a screen left open for days still marks the right day.
     */
    private val ticks: Flow<Instant>
) : ViewModel() {

    companion object {
        private const val TAG = "CalendarViewModel"

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = CalendarViewModel(
            symptomRepository = dependencies.symptomRepository,
            pressureRepository = dependencies.pressureRepository,
            userPreferences = dependencies.userPreferences,
            ticks = clockTicks()
        )
    }

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
            catchingFailures { symptomRepository.delete(entry.date) }
                .onSuccess { dismissBottomSheet() }
                // The calendar keeps showing the entry, so a failure is visible rather than
                // silent; the sheet stays open for the user to try again.
                .onFailure { Logger.e(TAG, "Failed to delete entry for ${entry.date}", it) }
        }
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(monthOffset, userPreferences.settings, ticks) { offset, settings, now ->
                // Days, months and the readings' bounds all counted at the location.
                CalendarQuery(calendarPeriod(offset, settings.location.zone, now), settings.alertThresholdHpa)
            }
                // A tick within the same day changes nothing, so it doesn't re-run the queries.
                .distinctUntilChanged()
                .collectLatest { (period, thresholdHpa) -> observePeriod(period, thresholdHpa) }
        }
    }

    private suspend fun observePeriod(period: CalendarPeriod, thresholdHpa: Float) {
        combine(
            symptomRepository.getEntriesInRange(period.month.firstDay, period.month.lastDay),
            pressureRepository.getReadingsInRange(period.readingsFrom, period.readingsUntil),
            symptomRepository.getEntriesInRange(period.statsFrom, period.today)
        ) { entries, readings, statsEntries -> Triple(entries, readings, statsEntries) }
            .collectLatest { (entries, readings, statsEntries) ->
                // Event detection walks the whole month's readings, so it runs off the main thread.
                val state = withContext(Dispatchers.Default) {
                    calendarState(period, entries, readings, statsEntries, thresholdHpa)
                }

                _uiState.value = _uiState.value.showing(state)
            }
    }
}

/** What the screen queries for: the period on show and the threshold its events are judged by. */
private data class CalendarQuery(val period: CalendarPeriod, val thresholdHpa: Float)

/** The computed state. The bottom sheet is the screen's own, so it carries over every data emission. */
private fun CalendarUiState.showing(state: CalendarState) = copy(
    currentMonth = state.month,
    today = state.today,
    entriesInMonth = state.entriesInMonth,
    highRiskDays = state.highRiskDays,
    monthLogCounts = state.monthLogCounts,
    lastMonthLogCounts = state.lastMonthLogCounts,
    last12MonthsLogCounts = state.last12MonthsLogCounts
)
