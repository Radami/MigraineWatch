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

data class MonthStats(
    val totalEpisodes: Int = 0,
    val severeCount: Int = 0,
    val afterDropCount: Int = 0,
    val totalDropCount: Int = 0
)

data class CalendarUiState(
    val currentMonth: YearMonth = YearMonth.now(),
    val entriesInMonth: Map<LocalDate, SymptomEntry> = emptyMap(),
    val pressureDropDays: Set<LocalDate> = emptySet(),
    val stats: MonthStats = MonthStats(),
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

                combine(
                    symptomRepository.getEntriesInRange(from, to),
                    pressureRepository.getReadingsInRange(fromInstant, toInstant)
                ) { entries, readings -> Pair(entries, readings) }
                    .collectLatest { (entries, readings) ->
                        val entriesMap = entries.associateBy { it.date }

                        val dropDays = withContext(Dispatchers.Default) {
                            computeDropDays(readings, settings.alertThresholdHpa, ZoneId.systemDefault())
                        }

                        val episodeEntries = entries.filter { it.severity != Severity.CLEAR }
                        val severeCount = entries.count { it.severity == Severity.MIGRAINE }
                        val afterDropCount = episodeEntries.count { e -> dropDays.contains(e.date) }

                        _uiState.value = CalendarUiState(
                            currentMonth = month,
                            entriesInMonth = entriesMap,
                            pressureDropDays = dropDays,
                            stats = MonthStats(
                                totalEpisodes = episodeEntries.size,
                                severeCount = severeCount,
                                afterDropCount = afterDropCount,
                                totalDropCount = dropDays.size
                            ),
                            showBottomSheet = _uiState.value.showBottomSheet,
                            selectedEntry = _uiState.value.selectedEntry
                        )
                    }
            }
        }
    }

    private fun computeDropDays(
        readings: List<PressureReading>,
        threshold: Float,
        zone: ZoneId
    ): Set<LocalDate> {
        val alerts = AlertDetector.detect(readings, threshold)
        return buildSet {
            alerts.forEach { alert ->
                var d = alert.start.atZone(zone).toLocalDate()
                val end = alert.end.atZone(zone).toLocalDate()
                while (!d.isAfter(end)) {
                    add(d)
                    d = d.plusDays(1)
                }
            }
        }
    }
}
