package com.example.migrainetracker.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.model.SymptomEntry
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.PressureRepository
import com.example.migrainetracker.data.repository.SymptomRepository
import com.example.migrainetracker.domain.AlertDetector
import com.example.migrainetracker.domain.AlertWindow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class TodayUiState(
    val currentPressure: Float? = null,
    val maxForecastDrop: Float = 0f,
    val historical: List<PressureReading> = emptyList(),
    val forecast: List<PressureReading> = emptyList(),
    val alertWindows: List<AlertWindow> = emptyList(),
    val alertThresholdHpa: Float = 6f,
    val weekEntries: Map<LocalDate, SymptomEntry?> = emptyMap(),
    val pressureDropDays: Set<LocalDate> = emptySet(),
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val symptomRepository: SymptomRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            pressureRepository.refresh()
        }
        observeData()
    }

    private fun observeData() {
        val now = Instant.now()
        // Query 4 days back for the chart, but 7 days ahead for full forecast analysis
        val from = now.minus(4, ChronoUnit.DAYS)
        val to = now.plus(7, ChronoUnit.DAYS)

        val today = LocalDate.now()
        val rangeStart = today.minusDays(6)
        val rangeEnd = today

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                symptomRepository.getEntriesInRange(rangeStart, rangeEnd),
                userPreferences.settings
            ) { readings, entries, settings ->
                Triple(readings, entries, settings)
            }.collectLatest { (readings, entries, settings) ->
                val hist = readings.filter { it.dateTime.isBefore(now) }
                val fore = readings.filter { !it.dateTime.isBefore(now) }

                val (alerts, maxDrop) = withContext(Dispatchers.Default) {
                    Pair(
                        AlertDetector.detect(fore, settings.alertThresholdHpa),
                        AlertDetector.maxForecastDrop(fore)
                    )
                }

                val entriesMap = (0..6).associate { d ->
                    val day = rangeStart.plusDays(d.toLong())
                    day to entries.find { it.date == day }
                }

                val zone = ZoneId.systemDefault()
                val dropDays = buildSet<LocalDate> {
                    alerts.forEach { alert ->
                        var d = alert.start.atZone(zone).toLocalDate()
                        val end = alert.end.atZone(zone).toLocalDate()
                        while (!d.isAfter(end)) {
                            add(d)
                            d = d.plusDays(1)
                        }
                    }
                }

                _uiState.value = TodayUiState(
                    currentPressure = hist.lastOrNull()?.pressureMsl
                        ?: fore.firstOrNull()?.pressureMsl,
                    maxForecastDrop = maxDrop,
                    historical = hist,
                    forecast = fore,
                    alertWindows = alerts,
                    alertThresholdHpa = settings.alertThresholdHpa,
                    weekEntries = entriesMap,
                    pressureDropDays = dropDays,
                    locationName = settings.location.name,
                    lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
                    isLoading = false
                )
            }
        }
    }
}
