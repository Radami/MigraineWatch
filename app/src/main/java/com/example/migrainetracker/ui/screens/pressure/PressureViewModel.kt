package com.example.migrainetracker.ui.screens.pressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.PressureRepository
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
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class TimeRange(val label: String, val hours: Long) {
    Hours24("24 hrs", 24),
    Hours48("48 hrs", 48),
    Days7("7 days", 168)
}

data class PressureUiState(
    val currentPressure: Float? = null,
    val historical: List<PressureReading> = emptyList(),
    val forecast: List<PressureReading> = emptyList(),
    val pastEvents: List<AlertWindow> = emptyList(),
    val alertWindows: List<AlertWindow> = emptyList(),
    val alertThresholdHpa: Float = 6f,
    val selectedRange: TimeRange = TimeRange.Days7,
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class PressureViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(PressureUiState())
    val uiState: StateFlow<PressureUiState> = _uiState.asStateFlow()

    private val selectedRange = MutableStateFlow(TimeRange.Days7)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (pressureRepository.isForecastStale()) {
                pressureRepository.refresh()
            }
        }
        observeData()
    }

    fun selectRange(range: TimeRange) {
        selectedRange.value = range
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                selectedRange,
                userPreferences.settings
            ) { range, settings -> Pair(range, settings) }
                .collectLatest { (range, settings) ->
                    val now = Instant.now()
                    // Always query the full stored history (30 days), not just the selected
                    // chart range: the "Last 3 events" card scans all of it. The chart only
                    // samples the instants it needs, so the wider list doesn't affect it.
                    val from = now.minus(30, ChronoUnit.DAYS)
                    val to = now.plus(7, ChronoUnit.DAYS)

                    pressureRepository.getReadingsInRange(from, to)
                        .collectLatest { readings ->
                            val hist = readings.filter { it.dateTime.isBefore(now) }
                            val fore = readings.filter { !it.dateTime.isBefore(now) }

                            val (alerts, pastEvents) = withContext(Dispatchers.Default) {
                                Pair(
                                    AlertDetector.detect(fore, settings.alertThresholdHpa),
                                    // Detect over the full series so an event that is still
                                    // underway keeps its true end (in the future) and is
                                    // excluded — it's a current alert, not history.
                                    AlertDetector.detect(readings, settings.alertThresholdHpa)
                                        .filter { it.end.isBefore(now) }
                                        .sortedByDescending { it.end }
                                        .take(3)
                                )
                            }

                            _uiState.value = PressureUiState(
                                currentPressure = hist.lastOrNull()?.pressureMsl
                                    ?: fore.firstOrNull()?.pressureMsl,
                                historical = hist,
                                forecast = fore,
                                pastEvents = pastEvents,
                                alertWindows = alerts,
                                alertThresholdHpa = settings.alertThresholdHpa,
                                selectedRange = range,
                                locationName = settings.location.name,
                                lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
                                isLoading = false
                            )
                        }
                }
        }
    }

}
