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
         * Detection reaches back a day so an event that is already underway is reported with
         * its true start rather than clipped at "now".
         */
        const val HISTORY_HOURS = 24L

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
        val from = now.minus(HISTORY_HOURS, ChronoUnit.HOURS)
        val window = readings.filter { !it.dateTime.isBefore(from) }
        return AlertDetector.detect(window, thresholdHpa)
    }

    /** Alerts for the user's current sensitivity, read straight from storage. */
    suspend fun currentAlerts(now: Instant = Instant.now()): List<AlertWindow> {
        val settings = userPreferences.settings.first()
        val readings = pressureRepository
            .getReadingsInRange(
                now.minus(HISTORY_HOURS, ChronoUnit.HOURS),
                now.plus(FORECAST_DAYS, ChronoUnit.DAYS)
            )
            .first()

        return alertsIn(readings, settings.alertThresholdHpa, now)
    }
}
