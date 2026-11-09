package com.radami.migrainewatch.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.SymptomFreeStreak
import com.radami.migrainewatch.ui.UiDependencies
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/** Everything the screen is built from, as one emission. */
private data class TodayInputs(
    /** The tick this emission is for; every date and relevance on the screen is judged from it. */
    val now: Instant,
    val readings: List<PressureReading>,
    val entries: List<SymptomEntry>,
    val settings: AppSettings,
    val refreshState: RefreshState
)

data class TodayUiState(
    /** Today first, then the days ahead. Empty until the first load finishes. */
    val outlook: List<DayOutlook> = emptyList(),
    /** Events under way or still ahead, earliest first. Finished events are dropped: nothing left to warn about. */
    val pendingAlerts: List<AlertWindow> = emptyList(),
    /** Where the first [pendingAlerts] entry sits relative to now, or null if none. Computed here since a composable has no clock. */
    val leadAlertPhase: AlertPhase? = null,
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    val symptomFreeStreak: SymptomFreeStreak? = null,
    val locationName: String = "",
    /** The location's zone, which every date and time on the screen is shown in. */
    val zone: TimeZone = LocationData().zone,
    val lastUpdated: Instant? = null,
    /**
     * Why [outlook] has nothing to show, null when it does. Non-null only when every day is
     * [com.radami.migrainewatch.domain.OutlookRisk.Unknown]; a short-but-partial forecast is not
     * a gap. Defaults to [OutlookGap.Loading] since there's no week before the first emission either.
     */
    val outlookGap: OutlookGap? = OutlookGap.Loading,
    /** Whether the first state has been computed. Lets a caller tell "computed" apart from "still default". */
    val isLoading: Boolean = true
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val pressureRepository: PressureRepository,
    private val symptomRepository: SymptomRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase,
    /**
     * The time now and at every tick after, from [clockTicks] outside tests. The screen's window
     * and "now" move with it, so a screen left open for days still shows the week ahead.
     */
    private val ticks: Flow<Instant>
) : ViewModel() {

    companion object {
        /** Far enough back to pin an already-underway event to its real start. */
        private const val HISTORY_DAYS = 4L

        /** Builds it from the app's dependencies, the same way on every platform. */
        fun create(dependencies: UiDependencies) = TodayViewModel(
            pressureRepository = dependencies.pressureRepository,
            symptomRepository = dependencies.symptomRepository,
            userPreferences = dependencies.userPreferences,
            alertUseCase = dependencies.alertUseCase,
            ticks = clockTicks()
        )
    }

    private val _uiState = MutableStateFlow(TodayUiState())
    val uiState: StateFlow<TodayUiState> = _uiState.asStateFlow()

    init {
        // Fetch runs in the repository's own scope; result comes back via refreshState.
        viewModelScope.launch {
            pressureRepository.refresh()
        }
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                // Re-queried on every tick, so the window follows the clock while the screen stays open.
                ticks.flatMapLatest { now -> readingsAround(now) },
                symptomRepository.getAllEntries(),
                userPreferences.settings,
                // Refreshes can come from the hourly worker or a location change, not just this screen.
                pressureRepository.refreshState
            ) { (now, readings), entries, settings, refreshState ->
                TodayInputs(now, readings, entries, settings, refreshState)
            }.collectLatest { (now, readings, entries, settings, refreshState) ->

                // The shared use case detects events, keeping the banner and scheduled
                // notifications in sync. The streak walks the full history, so it all runs off
                // the main thread.
                val state = withContext(Dispatchers.Default) {
                    todayState(
                        readings = readings,
                        entries = entries,
                        settings = settings,
                        refreshState = refreshState,
                        alerts = alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now),
                        coveredThrough = alertUseCase.coverageEnd(readings),
                        now = now
                    )
                }

                _uiState.value = state.toUiState()
            }
        }
    }

    /** The readings this screen draws from around [now], each emission paired with that [now]. */
    private fun readingsAround(now: Instant) = pressureRepository
        .getReadingsInRange(now - HISTORY_DAYS.days, now + PressureAlertUseCase.FORECAST_DAYS.days)
        .map { readings -> now to readings }
}

/** The computed state, marked as loaded. */
private fun TodayState.toUiState() = TodayUiState(
    outlook = outlook,
    pendingAlerts = pendingAlerts,
    leadAlertPhase = leadAlertPhase,
    alertThresholdHpa = alertThresholdHpa,
    symptomFreeStreak = symptomFreeStreak,
    locationName = locationName,
    zone = zone,
    lastUpdated = lastUpdated,
    outlookGap = outlookGap,
    isLoading = false
)
