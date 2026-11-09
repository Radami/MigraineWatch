package com.radami.migrainewatch.ui.screens.pressure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.ui.UiDependencies
import com.radami.migrainewatch.ui.components.ChartRendering
import com.radami.migrainewatch.ui.time.clockTicks
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

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
    /** The tick this emission is for; relevance and the current reading are judged from it. */
    val now: Instant,
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
    /** The location's zone, which the chart and every time on the screen are shown in. */
    val zone: TimeZone = LocationData().zone,
    val lastUpdated: Instant? = null,
    /** How the fetch is going. An empty table alone can't tell in-flight from failed from empty. */
    val refreshState: RefreshState = RefreshState.InFlight,
    /** Whether the first state has been computed. Lets a caller tell "computed" apart from "still default". */
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class PressureViewModel(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase,
    /**
     * The time now and at every tick after, from [clockTicks] outside tests. The screen's window
     * and "now" move with it, so a screen left open for days still shows the week ahead.
     */
    private val ticks: Flow<Instant>
) : ViewModel() {

    companion object {
        /** Covers the widest chip's span plus detection's lookback, with a day of slack for drift. */
        private const val HISTORY_DAYS = 4L

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = PressureViewModel(
            pressureRepository = dependencies.pressureRepository,
            userPreferences = dependencies.userPreferences,
            alertUseCase = dependencies.alertUseCase,
            ticks = clockTicks()
        )
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
        viewModelScope.launch {
            combine(
                // Re-queried on every tick, so the window follows the clock while the screen stays open.
                ticks.flatMapLatest { now -> readingsAround(now) },
                userPreferences.settings,
                pressureRepository.refreshState
            ) { (now, readings), settings, refreshState -> PressureInputs(now, readings, settings, refreshState) }
                .collectLatest { (now, readings, settings, refreshState) ->

                    // Event detection walks every reading, so it runs off the main thread.
                    val state = withContext(Dispatchers.Default) {
                        pressureState(
                            readings = readings,
                            settings = settings,
                            refreshState = refreshState,
                            alerts = alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now),
                            now = now
                        )
                    }

                    // The selected chip is the screen's own, so it survives every data emission.
                    _uiState.update { state.toUiState(selectedRange = it.selectedRange) }
                }
        }
    }

    /** The readings this screen draws from around [now], each emission paired with that [now]. */
    private fun readingsAround(now: Instant) = pressureRepository
        .getReadingsInRange(now - HISTORY_DAYS.days, now + PressureAlertUseCase.FORECAST_DAYS.days)
        .map { readings -> now to readings }

}

private fun PressureState.toUiState(selectedRange: TimeRange) = PressureUiState(
    currentPressure = currentPressure,
    readings = readings,
    alertWindows = alertWindows,
    alertThresholdHpa = alertThresholdHpa,
    selectedRange = selectedRange,
    locationName = locationName,
    zone = zone,
    lastUpdated = lastUpdated,
    refreshState = refreshState,
    isLoading = false
)
