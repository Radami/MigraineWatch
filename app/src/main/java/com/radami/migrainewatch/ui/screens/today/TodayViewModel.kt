package com.radami.migrainewatch.ui.screens.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
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
 * States the card used to report identically, and only one of them is about the network. An
 * empty table looks the same whichever it is, so all but the first of these come from asking
 * the repository how the fetching went rather than from reading the shape of the data: a
 * forecast that arrived and has since fallen behind is the ordinary case — a couple of days
 * offline does it — and blaming the connection for it states a cause nothing here has checked.
 *
 * Distinct from [TodayUiState.isLoading], which is about this screen rather than the data: it
 * says the ViewModel has not computed a state yet, where [Loading] says a fetch is out.
 */
enum class OutlookGap {

    /** A fetch is under way and nothing has arrived yet. The first load, ordinarily. */
    Loading,

    /** Readings arrived, but none of them reach far enough to say anything about any day. */
    ForecastBehind,

    /** Nothing has arrived, and the fetch that would have brought it failed. */
    FetchFailed,

    /** Nothing has arrived, and nothing was fetched for: no location is set yet. */
    NoLocation,

    /** Nothing has arrived, though the fetch that should have brought it reported success. */
    NoReadings
}

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
     *
     * Starts at [OutlookGap.Loading] rather than null: before the first emission there is no
     * week to draw either, and a card defaulting to "no gap" over an empty outlook would spend
     * that moment claiming a forecast it does not have.
     */
    val outlookGap: OutlookGap? = OutlookGap.Loading,
    /**
     * Whether the first state has been computed. Not the same question as [outlookGap]: this
     * one is about the screen, and turns over on the first emission whatever it contains.
     */
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
        // No dispatcher of its own: the fetch runs in the repository's scope, and the result
        // reaches this screen through refreshState rather than from here.
        viewModelScope.launch {
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
                userPreferences.settings,
                // The fetch a reader is waiting on is often not one this screen asked for: the
                // hourly worker and a change of location both drive refreshes underneath it.
                pressureRepository.refreshState
            ) { readings, entries, settings, refreshState ->
                TodayInputs(readings, entries, settings, refreshState)
            }.collectLatest { (readings, entries, settings, refreshState) ->
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

                val gap = when {
                    // A forecast that covers today and stops short of the week is the ordinary
                    // state of a forecast, not a gap: the strip draws its tail faded instead.
                    outlook.any { it.risk != OutlookRisk.Unknown } -> null

                    // Something arrived once and has since fallen behind. lastUpdated is
                    // non-null exactly here, so the card can date what it has rather than
                    // diagnose it.
                    readings.isNotEmpty() -> OutlookGap.ForecastBehind

                    // Nothing arrived, and nothing in the readings can say why. The fetch can.
                    else -> when (refreshState) {
                        RefreshState.InFlight -> OutlookGap.Loading
                        RefreshState.Failed -> OutlookGap.FetchFailed
                        RefreshState.NoLocation -> OutlookGap.NoLocation
                        RefreshState.Updated -> OutlookGap.NoReadings
                    }
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
