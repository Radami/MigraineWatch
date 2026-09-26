package com.radami.migrainewatch.ui.screens.pressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.ui.components.ChartRendering
import com.radami.migrainewatch.domain.PressureAlertUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * The chip above the chart: resolution and drawing mode. A min/max band only reads well over a
 * long step (a day); shorter steps use a plain line instead. Set per-chip, not inferred by the chart.
 */
enum class TimeRange(
    val label: String,
    val step: ChartStep,
    val rendering: ChartRendering
) {
    Hours24("24 hrs", ChartStep.ThreeHours, ChartRendering.Line),
    Hours48("48 hrs", ChartStep.SixHours, ChartRendering.Line),
    Days7("7 days", ChartStep.OneDay, ChartRendering.MinMaxBand)
}

/** Everything the screen is built from, as one emission. */
private data class PressureInputs(
    val readings: List<PressureReading>,
    val settings: AppSettings,
    val refreshState: RefreshState
)

data class PressureUiState(
    val currentPressure: Float? = null,
    /** Every reading the chart may draw from, sorted by time. */
    val readings: List<PressureReading> = emptyList(),
    val alertWindows: List<AlertWindow> = emptyList(),
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    val selectedRange: TimeRange = TimeRange.Days7,
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    /** How the fetch is going. An empty table alone can't tell in-flight from failed from empty. */
    val refreshState: RefreshState = RefreshState.InFlight,
    /** Whether the first state has been computed. Lets a caller tell "computed" apart from "still default". */
    val isLoading: Boolean = true
)

@HiltViewModel
class PressureViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase
) : ViewModel() {

    private companion object {
        /** Covers the widest chip's span plus detection's lookback, with a day of slack for drift. */
        const val HISTORY_DAYS = 4L
    }

    private val _uiState = MutableStateFlow(PressureUiState())
    val uiState: StateFlow<PressureUiState> = _uiState.asStateFlow()

    init {
        // Staleness check runs in Room's executor, the fetch in the repository's own scope.
        viewModelScope.launch {
            if (pressureRepository.isForecastStale()) {
                pressureRepository.refresh()
            }
        }
        observeData()
    }

    /** Written straight to state, not through the data flow: selection must be instant, not wait on a DB round trip. */
    fun selectRange(range: TimeRange) {
        _uiState.update { it.copy(selectedRange = range) }
    }

    private fun observeData() {
        // Fixed at screen open; a clock-following range would resubscribe the query every emission.
        val queryStart = Instant.now()
        val from = queryStart.minus(HISTORY_DAYS, ChronoUnit.DAYS)
        val to = queryStart.plus(PressureAlertUseCase.FORECAST_DAYS, ChronoUnit.DAYS)

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                userPreferences.settings,
                pressureRepository.refreshState
            ) { readings, settings, refreshState -> PressureInputs(readings, settings, refreshState) }
                .collectLatest { (readings, settings, refreshState) ->
                    // Re-evaluated per emission so nothing goes stale while the screen stays open.
                    val now = Instant.now()

                    // Shared use case, so this matches exactly what the Today banner and notifications describe.
                    val alerts = withContext(Dispatchers.Default) {
                        alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now)
                    }

                    // Last measured reading, or the earliest forecast one if opened before any measurement lands.
                    val current = readings.lastOrNull { it.dateTime.isBefore(now) }
                        ?: readings.firstOrNull()

                    _uiState.update { state ->
                        state.copy(
                            currentPressure = current?.pressureMsl,
                            readings = readings,
                            alertWindows = alerts,
                            alertThresholdHpa = settings.alertThresholdHpa,
                            locationName = settings.location.name,
                            lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
                            refreshState = refreshState,
                            isLoading = false
                        )
                    }
                }
        }
    }

}
