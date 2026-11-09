package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.model.toSymptomDay
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.SymptomFreeStreak
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Why the outlook has nothing to say about the week. Distinguished by asking the repository how
 * the fetch went, not by the shape of the data, so a stale forecast isn't wrongly blamed on the
 * network.
 */
enum class OutlookGap {

    /** Fetch under way, nothing arrived yet — usually the first load. */
    Loading,

    /** Readings arrived but don't reach far enough to say anything about any day. */
    ForecastBehind,

    /** Nothing arrived; the fetch that would have brought it failed. */
    FetchFailed,

    /** Nothing arrived; no location is set yet. */
    NoLocation,

    /** Nothing arrived, though the fetch reported success. */
    NoReadings
}

/** What the Today screen shows, worked out from its inputs at one moment. */
data class TodayState(
    /** Today first, then the days ahead. */
    val outlook: List<DayOutlook>,
    /** Events under way or still ahead, earliest first. Finished events are dropped: nothing left to warn about. */
    val pendingAlerts: List<AlertWindow>,
    /** Where the first [pendingAlerts] entry sits relative to now, or null if none. */
    val leadAlertPhase: AlertPhase?,
    val alertThresholdHpa: Float,
    val symptomFreeStreak: SymptomFreeStreak?,
    val locationName: String,
    /** The location's zone, which every date and time on the screen is shown in. */
    val zone: TimeZone,
    val lastUpdated: Instant?,
    /**
     * Why [outlook] has nothing to show, null when it does. Non-null only when every day is
     * [OutlookRisk.Unknown]; a short-but-partial forecast is not a gap.
     */
    val outlookGap: OutlookGap?
)

/**
 * Builds the Today screen from what it observes. Pure, so every rule can be tested without a
 * device, a database or a clock. Walks the whole symptom history, so callers on a UI thread
 * should run it in the background.
 *
 * @param alerts events detected in [readings] for the user's threshold, by the shared use case
 *   that also drives notifications, so the banner and the warnings can't disagree.
 * @param coveredThrough where [readings] stop describing, also from that use case: the outlook
 *   can only call a day clear as far as the readings actually reach.
 */
fun todayState(
    readings: List<PressureReading>,
    entries: List<SymptomEntry>,
    settings: AppSettings,
    refreshState: RefreshState,
    alerts: List<AlertWindow>,
    coveredThrough: Instant?,
    now: Instant
): TodayState {
    val zone = settings.location.zone

    // The location's today, not the device's, read from the same instant as everything else.
    val today = now.toLocalDateTime(zone).date

    val outlook = DayOutlook.forecast(alerts = alerts, coveredThrough = coveredThrough, today = today, zone = zone)

    // Banner leads with the earliest still-live event, i.e. whichever one the user is in.
    val pending = alerts.filter { it.end > now }

    return TodayState(
        outlook = outlook,
        pendingAlerts = pending,
        leadAlertPhase = pending.firstOrNull()?.let { AlertPhase.of(it, now) },
        alertThresholdHpa = settings.alertThresholdHpa,
        symptomFreeStreak = SymptomFreeStreak.from(entries.map { it.toSymptomDay() }, today),
        locationName = settings.location.name,
        zone = zone,
        lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
        outlookGap = outlookGap(outlook, readings, refreshState)
    )
}

private fun outlookGap(
    outlook: List<DayOutlook>,
    readings: List<PressureReading>,
    refreshState: RefreshState
): OutlookGap? {
    // Partial-week coverage is normal, not a gap; the strip fades its tail instead.
    if (outlook.any { it.risk != OutlookRisk.Unknown }) return null

    // Arrived once, since fallen behind; lastUpdated is non-null here so it can be dated.
    if (readings.isNotEmpty()) return OutlookGap.ForecastBehind

    // Nothing arrived; ask the fetch state why.
    return when (refreshState) {
        // Room delivers a committed write a few hops after the fetch returns, so "succeeded but
        // table empty" still means first load in progress.
        RefreshState.InFlight, RefreshState.Updated -> OutlookGap.Loading

        RefreshState.NoReadings -> OutlookGap.NoReadings
        RefreshState.Failed -> OutlookGap.FetchFailed
        RefreshState.NoLocation -> OutlookGap.NoLocation
    }
}
