package com.example.migrainetracker.domain

import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.repository.PressureRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single definition of "which pressure events count right now".
 *
 * The Today screen and the notification scheduler both go through here so they cannot drift
 * apart: a banner listing three events while a notification announces a fourth would be worse
 * than either being wrong on its own.
 */
@Singleton
class PressureAlertUseCase @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences
) {
    companion object {
        /**
         * How recently an event must have finished to still count as current. An event that
         * ended within the last day is what the Today screen marks and what the calendar
         * colours, so it stays in the result; anything older is history.
         */
        const val RELEVANCE_HOURS = 24L

        /**
         * How far back detection reads, which is deliberately further than [RELEVANCE_HOURS].
         *
         * An event that is already underway has its peak behind us. Detecting inside a window
         * that begins at `now - RELEVANCE_HOURS` pins the start to the window edge instead of
         * to the real peak, so the reported start walks forward an hour on every refresh and
         * one continuous event looks like a new event each time — new work name, new
         * notification id, nothing matching the delivered history. Reaching back three days is
         * what gives an in-progress event one stable identity.
         */
        const val DETECTION_HISTORY_HOURS = 72L

        /** The forecast horizon Open-Meteo gives us. */
        const val FORECAST_DAYS = 7L
    }

    /**
     * Alerts within [readings], which the caller has already collected. Callers that hold a
     * flow of readings for other reasons (the Today screen holds them for its chart) use this
     * rather than re-reading the database.
     */
    fun alertsIn(
        readings: List<PressureReading>,
        thresholdHpa: Float,
        now: Instant
    ): List<AlertWindow> {
        val detectFrom = now.minus(DETECTION_HISTORY_HOURS, ChronoUnit.HOURS)
        val window = readings.filter { !it.dateTime.isBefore(detectFrom) }

        // The extra history exists only to find true starts, not to widen the result: an event
        // that finished before the relevance window is reported to nobody.
        val relevantFrom = now.minus(RELEVANCE_HOURS, ChronoUnit.HOURS)
        return AlertDetector.detect(window, thresholdHpa).filter { it.end.isAfter(relevantFrom) }
    }

    /** Alerts for the user's current sensitivity, read straight from storage. */
    suspend fun currentAlerts(now: Instant = Instant.now()): List<AlertWindow> {
        val settings = userPreferences.settings.first()
        val readings = pressureRepository
            .getReadingsInRange(
                now.minus(DETECTION_HISTORY_HOURS, ChronoUnit.HOURS),
                now.plus(FORECAST_DAYS, ChronoUnit.DAYS)
            )
            .first()

        return alertsIn(readings, settings.alertThresholdHpa, now)
    }
}
