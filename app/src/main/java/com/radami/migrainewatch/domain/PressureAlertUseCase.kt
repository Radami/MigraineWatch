package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single definition of "which pressure events count right now". The Today screen and the
 * notification scheduler both go through here so they can't drift apart.
 */
@Singleton
class PressureAlertUseCase @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences
) {
    companion object {
        /**
         * How recently an event must have finished to still count as current. Kept because
         * screens look backwards as well as forwards (chart history, day-outlook). Does not
         * license a warning: callers announcing to the user filter finished events out
         * themselves (see [AlertNotificationDecider.decide]).
         */
        const val RELEVANCE_HOURS = 24L

        /**
         * How far back detection reads; deliberately further than [RELEVANCE_HOURS]. A shorter
         * window would pin an underway event's start to the window edge instead of its real
         * peak, making it look like a new event on every refresh.
         */
        const val DETECTION_HISTORY_HOURS = 72L

        /** The forecast horizon Open-Meteo gives us. */
        const val FORECAST_DAYS = 7L

        /**
         * The cadence Open-Meteo publishes at. A reading stands for the hour it opens, not an
         * instant — see [coverageEnd].
         */
        const val READING_INTERVAL_HOURS = 1L
    }

    /**
     * The instant [readings] stop describing, or null when there are none. Deliberately not the
     * last reading's timestamp: the hour that reading opens is covered too, or the final day of
     * the forecast would look short of data.
     */
    fun coverageEnd(readings: List<PressureReading>): Instant? =
        readings.maxOfOrNull { it.dateTime }?.plus(READING_INTERVAL_HOURS, ChronoUnit.HOURS)

    /**
     * Alerts within [readings], which the caller has already collected (e.g. the Today screen's
     * chart data), avoiding a re-read of the database.
     */
    fun alertsIn(
        readings: List<PressureReading>,
        thresholdHpa: Float,
        now: Instant
    ): List<AlertWindow> {
        val detectFrom = now.minus(DETECTION_HISTORY_HOURS, ChronoUnit.HOURS)
        val window = readings.filter { !it.dateTime.isBefore(detectFrom) }

        // Extra history is only to find true starts, not to widen the result.
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
