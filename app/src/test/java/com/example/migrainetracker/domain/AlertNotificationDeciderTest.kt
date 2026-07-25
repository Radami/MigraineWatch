package com.example.migrainetracker.domain

import com.example.migrainetracker.data.model.NotifiedAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlertNotificationDeciderTest {

    private val now: Instant = Instant.parse("2026-07-25T12:00:00Z")

    private fun alert(
        startHoursFromNow: Long,
        durationHours: Long = 20,
        direction: String = "drop",
        delta: Float = 12f
    ) = AlertWindow(
        start = now.plus(Duration.ofHours(startHoursFromNow)),
        end = now.plus(Duration.ofHours(startHoursFromNow + durationHours)),
        delta = delta,
        direction = direction
    )

    private fun notified(alert: AlertWindow, thresholdHpa: Float = 6f) = NotifiedAlert(
        startDateTime = alert.start,
        direction = alert.direction,
        thresholdHpa = thresholdHpa,
        notifiedDateTime = now
    )

    private fun decide(
        alerts: List<AlertWindow>,
        alreadyNotified: List<NotifiedAlert> = emptyList(),
        notificationsEnabled: Boolean = true
    ) = AlertNotificationDecider.decide(alerts, alreadyNotified, notificationsEnabled, now)

    @Test
    fun `schedules a warning twelve hours before the event starts`() {
        val event = alert(startHoursFromNow = 36)

        val pending = decide(listOf(event))

        assertEquals(1, pending.size)
        assertEquals(now.plus(Duration.ofHours(24)), pending[0].notifyAt)
        assertEquals(event, pending[0].alert)
    }

    @Test
    fun `warns immediately about an event found inside its own lead time`() {
        // A forecast refresh can surface an event only 3 h out; late is better than never.
        val pending = decide(listOf(alert(startHoursFromNow = 3)))

        assertEquals(1, pending.size)
        assertEquals(now, pending[0].notifyAt)
    }

    @Test
    fun `warns immediately about an event already underway`() {
        val pending = decide(listOf(alert(startHoursFromNow = -6)))

        assertEquals(1, pending.size)
        assertEquals(now, pending[0].notifyAt)
    }

    @Test
    fun `ignores events that have already finished`() {
        val pending = decide(listOf(alert(startHoursFromNow = -40, durationHours = 20)))

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `does not repeat an event the user has been told about`() {
        val event = alert(startHoursFromNow = 36)

        val pending = decide(listOf(event), alreadyNotified = listOf(notified(event)))

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `treats a forecast that nudges the start as the same event`() {
        val asFirstSeen = alert(startHoursFromNow = 36)
        val asRefetched = alert(startHoursFromNow = 40)

        val pending = decide(listOf(asRefetched), alreadyNotified = listOf(notified(asFirstSeen)))

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `treats a start that moves beyond the tolerance as a new event`() {
        val asFirstSeen = alert(startHoursFromNow = 36)
        val muchLater = alert(startHoursFromNow = 36 + 7)

        val pending = decide(listOf(muchLater), alreadyNotified = listOf(notified(asFirstSeen)))

        assertEquals(1, pending.size)
    }

    @Test
    fun `a drop and a rise starting together are warned about separately`() {
        val drop = alert(startHoursFromNow = 36, direction = "drop")
        val rise = alert(startHoursFromNow = 36, direction = "rise")

        val pending = decide(listOf(drop, rise), alreadyNotified = listOf(notified(drop)))

        assertEquals(listOf(rise), pending.map { it.alert })
    }

    @Test
    fun `re-announces nothing when sensitivity is raised then lowered again`() {
        // The event was notified at 6 hPa. Moving to Low dropped it from the forecast's
        // qualifying set; moving back to High brings it back — but the user already knows.
        val event = alert(startHoursFromNow = 36, delta = 6.5f)

        val pending = decide(listOf(event), alreadyNotified = listOf(notified(event, thresholdHpa = 6f)))

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `schedules nothing while notifications are switched off`() {
        val pending = decide(listOf(alert(startHoursFromNow = 36)), notificationsEnabled = false)

        assertTrue(pending.isEmpty())
    }

    @Test
    fun `returns the full qualifying set so callers can cancel what is missing`() {
        val soon = alert(startHoursFromNow = 20, direction = "drop")
        val later = alert(startHoursFromNow = 60, direction = "rise")

        val pending = decide(listOf(later, soon))

        assertEquals(listOf(soon, later), pending.map { it.alert })
    }
}
