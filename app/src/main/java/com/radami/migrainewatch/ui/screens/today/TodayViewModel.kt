package com.radami.migrainewatch.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Why the outlook has nothing to say about the week.
 *
 * Two states the card used to report identically, and only one of them is about the network.
 * A forecast that arrived and has since fallen behind is the ordinary case — a couple of days
 * offline does it — and blaming the connection for it states a cause nothing here has checked.
 */
enum class OutlookGap {

    /** No readings at all in the window, so nothing has successfully arrived to fall behind. */
    NoReadings,

    /** Readings arrived, but none of them reach far enough to say anything about any day. */
    ForecastBehind
}

data class TodayUiState(
    /** Today first, then the days ahead. Empty until the first load finishes. */
    val outlook: List<DayOutlook> = emptyList(),
    /**
     * Events under way or still ahead, earliest first. Deliberately not every event the
     * detector returned: one that has finished still marks its day in [outlook], but the
     * banner warns, and there is nothing to warn about once an event is over.
     */
    val pendingAlerts: List<AlertWindow> = emptyList(),
    /**
     * Where the first of [pendingAlerts] sits relative to now, or null when there are none.
     *
     * Worked out here rather than in the banner, for the same reason the list itself is
     * filtered here: a composable has no clock, and an event's phase turns over on its own
     * while nothing redraws.
     */
    val leadAlertPhase: AlertPhase? = null,
    val alertThresholdHpa: Float = AlertSensitivity.Default.thresholdHpa,
    val symptomFreeStreak: SymptomFreeStreak? = null,
    val locationName: String = "",
    val lastUpdated: Instant? = null,
    /**
     * Why [outlook] has nothing to show, or null when it has. Non-null exactly when every day
     * came back [com.radami.migrainewatch.domain.OutlookRisk.Unknown] — a forecast that covers
     * today but stops short of the week is not a gap, and the strip shows it with its tail
     * faded rather than reporting a failure.
     */
    val outlookGap: OutlookGap? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val symptomRepository: SymptomRepository,
    private val userPreferences: UserPreferences,
    private val alertUseCase: PressureAlertUseCase
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
        // Reach back far enough for detection to pin an event that is already underway to its
        // real start, and forward across the whole outlook.
        val queryStart = Instant.now()
        val from = queryStart.minus(4, ChronoUnit.DAYS)
        val to = queryStart.plus(PressureAlertUseCase.FORECAST_DAYS, ChronoUnit.DAYS)

        viewModelScope.launch {
            combine(
                pressureRepository.getReadingsInRange(from, to),
                symptomRepository.getAllEntries(),
                userPreferences.settings
            ) { readings, entries, settings ->
                Triple(readings, entries, settings)
            }.collectLatest { (readings, entries, settings) ->
                // Re-evaluate "now" on every emission so the relevance of an event doesn't go
                // stale while the screen stays open.
                val now = Instant.now()

                // Detection goes through the shared use case so the banner and the scheduled
                // notifications always describe the same set of events. The streak walks the
                // user's entire history, so it stays off the main thread alongside it.
                val (alerts, streak, outlook) = withContext(Dispatchers.Default) {
                    val detected = alertUseCase.alertsIn(readings, settings.alertThresholdHpa, now)

                    // Today is read here rather than hoisted out of the flow so the count is at
                    // least right for every emission. It can still go stale if the screen is left
                    // open across midnight with nothing else emitting.
                    val today = LocalDate.now()

                    // The outlook can only call a day clear as far as the readings reach, so it
                    // is told where they stop rather than assuming a full forecast arrived. How
                    // far that is belongs to the use case: the last reading is not the last
                    // moment covered.
                    val days = DayOutlook.forecast(
                        alerts = detected,
                        coveredThrough = alertUseCase.coverageEnd(readings),
                        today = today,
                        zone = ZoneId.systemDefault()
                    )

                    Triple(detected, SymptomFreeStreak.from(entries, today), days)
                }

                // The banner leads with the earliest still-live event, which is the one under
                // way when there is one — it is the event the user is actually in.
                val pending = alerts.filter { it.end.isAfter(now) }

                // Readings but no usable day means what arrived stops short of today's end;
                // no readings at all means nothing arrived. Only the second implicates the
                // connection, and lastUpdated is non-null exactly in the first, so the card
                // can date what it has.
                val gap = when {
                    readings.isEmpty() -> OutlookGap.NoReadings
                    outlook.all { it.risk == OutlookRisk.Unknown } -> OutlookGap.ForecastBehind
                    else -> null
                }

                _uiState.value = TodayUiState(
                    outlook = outlook,
                    pendingAlerts = pending,
                    leadAlertPhase = pending.firstOrNull()?.let { AlertPhase.of(it, now) },
                    alertThresholdHpa = settings.alertThresholdHpa,
                    symptomFreeStreak = streak,
                    locationName = settings.location.name,
                    lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
                    outlookGap = gap,
                    isLoading = false
                )
            }
        }
    }
}
