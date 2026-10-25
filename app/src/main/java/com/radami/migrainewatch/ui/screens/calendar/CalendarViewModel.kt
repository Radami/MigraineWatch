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
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinTimeZone
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlin.time.toKotlinInstant
import com.radami.migrainewatch.data.model.toPressureSample

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(LocationData().zone),
    /** Today at the location, which the grid marks and the statistics count back from. */
    val today: LocalDate = LocalDate.now(LocationData().zone),
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
                val zone = settings.location.zone
                val today = LocalDate.now(zone)
                val thisMonth = YearMonth.from(today)
                val month = thisMonth.plusMonths(offset)

                val from = month.atDay(1)
                val to = month.atEndOfMonth()
                val fromInstant = from.atStartOfDay(zone).toInstant()
                val toInstant = to.plusDays(1).atStartOfDay(zone).toInstant()

                val statsFrom = today.minusMonths(12)

                combine(
                    symptomRepository.getEntriesInRange(from.toKotlinLocalDate(), to.toKotlinLocalDate()),
                    pressureRepository.getReadingsInRange(fromInstant.toKotlinInstant(), toInstant.toKotlinInstant()),
                    symptomRepository.getEntriesInRange(statsFrom.toKotlinLocalDate(), today.toKotlinLocalDate())
                ) { entries, readings, statsEntries -> Triple(entries, readings, statsEntries) }
                    .collectLatest { (entries, readings, statsEntries) ->
                        val entriesMap = entries.associateBy { it.date.toJavaLocalDate() }

                        val highRiskDays = withContext(Dispatchers.Default) {
                            val alerts = AlertDetector.detect(
                                readings.map { it.toPressureSample() },
                                settings.alertThresholdHpa
                            )
                            AlertDetector.eventDays(alerts, zone.toKotlinTimeZone())
                                .mapTo(HashSet()) { it.toJavaLocalDate() }
                        }

                        val lastMonth = thisMonth.minusMonths(1)
                        val monthLogCounts = statsEntries
                            .filter { YearMonth.from(it.date.toJavaLocalDate()) == thisMonth }
                            .groupingBy { it.severity }.eachCount()
                        val lastMonthLogCounts = statsEntries
                            .filter { YearMonth.from(it.date.toJavaLocalDate()) == lastMonth }
                            .groupingBy { it.severity }.eachCount()
                        // The fetched range is exactly the rolling 12-month window.
                        val last12MonthsLogCounts = statsEntries.groupingBy { it.severity }.eachCount()

                        _uiState.value = CalendarUiState(
                            currentMonth = month,
                            today = today,
                            entriesInMonth = entriesMap,
                            highRiskDays = highRiskDays,
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
