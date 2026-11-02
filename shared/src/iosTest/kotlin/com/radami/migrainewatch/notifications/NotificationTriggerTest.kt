package com.radami.migrainewatch.notifications

import platform.Foundation.secondsFromGMT
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class NotificationTriggerTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-11-01T02:35:00Z")
        val FIRE_AT: Instant = Instant.parse("2026-11-01T20:00:00Z")
    }

    @Test
    fun aWarningAheadFiresOnItsExactDate() {
        val trigger = assertIs<UNCalendarNotificationTrigger>(triggerFor(FIRE_AT, NOW))

        assertEquals(FIRE_AT.epochSeconds.toDouble(), trigger.nextTriggerDate()?.timeIntervalSince1970)
    }

    @Test
    fun theFireDateIsTheSameInstantWhateverTheDeviceZone() {
        val components = fireDateComponents(FIRE_AT)

        assertEquals(listOf(2026L, 11L, 1L, 20L, 0L, 0L), with(components) { listOf(year, month, day, hour, minute, second) })
        assertEquals(0L, components.timeZone?.secondsFromGMT)
    }

    @Test
    fun aWarningDueNowGoesOutOnTheShortestTimer() {
        val trigger = assertIs<UNTimeIntervalNotificationTrigger>(triggerFor(NOW, NOW))

        assertEquals(1.0, trigger.timeInterval)
    }

    @Test
    fun aWarningJustUnderASecondAwayAlsoGoesOutOnTheTimer() {
        assertIs<UNTimeIntervalNotificationTrigger>(triggerFor(NOW + 900.milliseconds, NOW))
    }

    @Test
    fun aWarningWhoseTimeHasPassedStillGoesOut() {
        assertIs<UNTimeIntervalNotificationTrigger>(triggerFor(NOW - 1.hours, NOW))
    }
}
