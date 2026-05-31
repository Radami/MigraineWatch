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
    val maxForecastDrop: Float = 0f,
    val trendLabel: String = "",
    val historical: List<PressureReading> = emptyList(),
    val forecast: List<PressureReading> = emptyList(),
    val next12Hours: List<PressureReading> = emptyList(),
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
                    val from = now.minus(range.hours, ChronoUnit.HOURS)
                    val to = now.plus(7, ChronoUnit.DAYS)

                    pressureRepository.getReadingsInRange(from, to)
                        .collectLatest { readings ->
                            val hist = readings.filter { it.dateTime.isBefore(now) }
                            val fore = readings.filter { !it.dateTime.isBefore(now) }
                            val next12 = fore.filter {
                                it.dateTime.isBefore(now.plus(12, ChronoUnit.HOURS))
                            }

                            val (alerts, maxDrop) = withContext(Dispatchers.Default) {
                                Pair(
                                    AlertDetector.detect(fore, settings.alertThresholdHpa),
                                    AlertDetector.maxForecastDrop(fore)
                                )
                            }

                            val trend = buildTrendLabel(hist, fore)

                            _uiState.value = PressureUiState(
                                currentPressure = hist.lastOrNull()?.pressureMsl
                                    ?: fore.firstOrNull()?.pressureMsl,
                                maxForecastDrop = maxDrop,
                                trendLabel = trend,
                                historical = hist,
                                forecast = fore,
                                next12Hours = next12,
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

    private fun buildTrendLabel(hist: List<PressureReading>, fore: List<PressureReading>): String {
        val recent = hist.takeLast(6)
        if (recent.size < 2) return ""
        val delta = recent.last().pressureMsl - recent.first().pressureMsl
        return when {
            delta < -3f -> "Falling · front incoming"
            delta > 3f -> "Rising · clearing"
            else -> "Steady"
        }
    }
}
