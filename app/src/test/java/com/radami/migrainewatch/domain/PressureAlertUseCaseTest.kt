package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The relevance window, which decides how long a finished event goes on counting as current.
 * Pinned here because the answer is a product decision rather than a detection one: the
 * Pressure screen's history half and the Today outlook both want an event that has just
 * passed, while anything announcing to the user does not.
 */
class PressureAlertUseCaseTest {

    private companion object {
        const val THRESHOLD_HPA = 5f
    }

    private val useCase = PressureAlertUseCase(mockk<PressureRepository>(), mockk<UserPreferences>())

    private val now = Instant.parse("2026-08-23T12:00:00Z")

    /** A 10 hPa drop over 24 h, ending [endedHoursAgo] before [now]. */
    private fun dropEnding(endedHoursAgo: Long): List<PressureReading> {
        val end = now.minus(endedHoursAgo, ChronoUnit.HOURS)
        val start = end.minus(24, ChronoUnit.HOURS)
        return listOf(
            PressureReading(start, 1020f, 1020f, now),
            PressureReading(end, 1010f, 1010f, now)
        )
    }

    @Test
    fun `an event that finished within the day is still current`() {
        val alerts = useCase.alertsIn(dropEnding(endedHoursAgo = 7), THRESHOLD_HPA, now)

        assertEquals(1, alerts.size)
        assertTrue(alerts.first().end.isBefore(now))
    }

    @Test
    fun `an event that finished longer ago than the window is history`() {
        // Still well inside the 72 h detection reads over, so it is the relevance window and
        // not the absence of readings that drops it.
        val alerts = useCase.alertsIn(dropEnding(endedHoursAgo = 30), THRESHOLD_HPA, now)

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `an event still under way is current`() {
        val readings = listOf(
            PressureReading(now.minus(12, ChronoUnit.HOURS), 1020f, 1020f, now),
            PressureReading(now.plus(12, ChronoUnit.HOURS), 1010f, 1010f, now)
        )

        val alerts = useCase.alertsIn(readings, THRESHOLD_HPA, now)

        assertEquals(1, alerts.size)
        assertTrue(alerts.first().end.isAfter(now))
    }
}
