package com.radami.migrainewatch.ui.screens.pressure

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertWindow
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** What the Pressure screen shows, worked out from its inputs at one moment. */
data class PressureState(
    /** The pressure right now, or null while nothing has arrived. */
    val currentPressure: Float?,
    /** Every reading the chart may draw from, sorted by time. */
    val readings: List<PressureReading>,
    val alertWindows: List<AlertWindow>,
    val alertThresholdHpa: Float,
    val locationName: String,
    /** The location's zone, which the chart and every time on the screen are shown in. */
    val zone: TimeZone,
    val lastUpdated: Instant?,
    /** How the fetch is going. An empty table alone can't tell in-flight from failed from empty. */
    val refreshState: RefreshState
)

/**
 * Builds the Pressure screen from what it observes. Pure, so every rule can be tested without a
 * device, a database or a clock.
 *
 * @param readings sorted by time, as the repository returns them.
 * @param alerts events detected in [readings] for the user's threshold, by the shared use case,
 *   so the chart marks exactly what the Today banner and the notifications describe.
 */
fun pressureState(
    readings: List<PressureReading>,
    settings: AppSettings,
    refreshState: RefreshState,
    alerts: List<AlertWindow>,
    now: Instant
): PressureState {
    // Last measured reading, or the earliest forecast one if opened before any measurement lands.
    val current = readings.lastOrNull { it.dateTime < now }
        ?: readings.firstOrNull()

    return PressureState(
        currentPressure = current?.pressureMsl,
        readings = readings,
        alertWindows = alerts,
        alertThresholdHpa = settings.alertThresholdHpa,
        locationName = settings.location.name,
        zone = settings.location.zone,
        lastUpdated = readings.maxOfOrNull { it.fetchedDateTime },
        refreshState = refreshState
    )
}
