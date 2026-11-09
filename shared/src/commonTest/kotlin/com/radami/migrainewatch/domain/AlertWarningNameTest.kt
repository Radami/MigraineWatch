package com.radami.migrainewatch.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AlertWarningNameTest {

    private companion object {
        /** The name a warning for a drop starting 2026-08-25T00:00Z has always carried. */
        const val PINNED_NAME = "alert_notification_drop_496560"

        val HOUR_START: Instant = Instant.parse("2026-08-25T00:00:00Z")
    }

    private fun pendingFor(start: Instant, direction: PressureDirection) = PendingAlertNotification(
        alert = AlertWindow(start = start, end = start + 600.minutes, delta = 9f, direction = direction),
        notifyAt = start - 720.minutes,
        phase = AlertPhase.AHEAD
    )

    @Test
    fun aNameReadsBackAsTheEventItWasMadeFor() {
        assertEquals(WarningEvent(PressureDirection.DROP, HOUR_START), AlertWarningName.parse(PINNED_NAME))
    }

    @Test
    fun anEventStartingWithinTheHourReadsBackAsThatHour() {
        val name = AlertWarningName.of(pendingFor(HOUR_START + 40.minutes, PressureDirection.RISE))

        assertEquals(WarningEvent(PressureDirection.RISE, HOUR_START), AlertWarningName.parse(name))
    }

    @Test
    fun aNameThisBuildCannotReadGivesNoEvent() {
        assertNull(AlertWarningName.parse("pressure_fetch"))
        assertNull(AlertWarningName.parse("alert_notification_sideways_496560"))
        assertNull(AlertWarningName.parse("alert_notification_drop_soon"))
        assertNull(AlertWarningName.parse("alert_notification_drop"))
    }
}
