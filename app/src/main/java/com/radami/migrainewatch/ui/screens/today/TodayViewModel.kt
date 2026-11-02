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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaZoneId
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaInstant

/** Everything the screen is built from, as one emission. */
private data class TodayInputs(
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
    val zone: ZoneId = LocationData().zone.toJavaZoneId(),
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

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val symptomRepository: SymptomRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase
) : ViewModel() {

    private companion object {
        /** Far enough back to pin an already-underway event to its real start. */
        const val HISTORY_DAYS = 4L
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
        val queryStart = Clock.System.now()
        val from = queryStart - HISTORY_DAYS.days
        val to = queryStart + PressureAlertUseCase.FORECAST_DAYS.days

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                symptomRepository.getAllEntries(),
                userPreferences.settings,
                // Refreshes can come from the hourly worker or a location change, not just this screen.
                pressureRepository.refreshState
            ) { readings, entries, settings, refreshState ->
                TodayInputs(readings, entries, settings, refreshState)
            }.collectLatest { (readings, entries, settings, refreshState) ->
                // Re-evaluated per emission so relevance doesn't go stale while the screen stays
                // open, and so today can't go stale across midnight while nothing else emits.
                val now = Clock.System.now()

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
}

/** The screen still reads `java.time` types until it moves to the shared UI. */
private fun TodayState.toUiState() = TodayUiState(
    outlook = outlook,
    pendingAlerts = pendingAlerts,
    leadAlertPhase = leadAlertPhase,
    alertThresholdHpa = alertThresholdHpa,
    symptomFreeStreak = symptomFreeStreak,
    locationName = locationName,
    zone = zone.toJavaZoneId(),
    lastUpdated = lastUpdated?.toJavaInstant(),
    outlookGap = outlookGap,
    isLoading = false
)
