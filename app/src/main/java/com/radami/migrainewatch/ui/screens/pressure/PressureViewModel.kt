package com.radami.migrainewatch.ui.screens.pressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
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
 * The chip above the chart: the resolution each one asks for, and what it asks to be drawn.
 *
 * A band says how far pressure moved inside a step, so it is worth drawing only where a step
 * is long enough to have moved: over a day it opens into something readable, over three hours
 * it collapses onto the line it is drawn around and reads as a thicker line. So the hourly
 * chips take the line and the daily chip takes the band — a per-chip choice rather than a rule
 * the chart infers, which is what lets the two be compared by changing one value here.
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

data class PressureUiState(
    val currentPressure: Float? = null,
    /** Every reading the chart may draw from, sorted by time. */
    val readings: List<PressureReading> = emptyList(),
    val alertWindows: List<AlertWindow> = emptyList(),
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    val selectedRange: TimeRange = TimeRange.Days7,
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class PressureViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase
) : ViewModel() {

    private companion object {
        /**
         * How much history to read. The widest chip draws three and a half days of it, and
         * detection reads back [PressureAlertUseCase.DETECTION_HISTORY_HOURS] to find where an
         * event underway began; a whole day over the longer of the two absorbs the drift
         * between opening the screen and each later emission.
         */
        const val HISTORY_DAYS = 4L
    }

    private val _uiState = MutableStateFlow(PressureUiState())
    val uiState: StateFlow<PressureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            if (pressureRepository.isForecastStale()) {
                pressureRepository.refresh()
            }
        }
        observeData()
    }

    /**
     * The range only picks the resolution the chart draws at, so it is written straight to the
     * state rather than fed back through the data flow: the chip has to select on the tap, not
     * a database round trip later, and none of the data below depends on it.
     */
    fun selectRange(range: TimeRange) {
        _uiState.update { it.copy(selectedRange = range) }
    }

    private fun observeData() {
        // Fixed when the screen opens, as the Today screen's is: a range that slid with the
        // clock would resubscribe the query on every emission.
        val queryStart = Instant.now()
        val from = queryStart.minus(HISTORY_DAYS, ChronoUnit.DAYS)
        val to = queryStart.plus(PressureAlertUseCase.FORECAST_DAYS, ChronoUnit.DAYS)

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                userPreferences.settings
            ) { readings, settings -> Pair(readings, settings) }
                .collectLatest { (readings, settings) ->
                    // Re-evaluated per emission so the current reading and the relevance of an
                    // event don't go stale while the screen stays open.
                    val now = Instant.now()

                    // Detection goes through the shared use case, so the windows shaded here
                    // are exactly the ones the Today banner and the notifications describe
                    // rather than a second opinion on the same data.
                    val alerts = withContext(Dispatchers.Default) {
                        alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now)
                    }

                    // The last measured reading, or the earliest forecast one if the screen is
                    // open before any measurement has landed.
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
                            isLoading = false
                        )
                    }
                }
        }
    }

}
