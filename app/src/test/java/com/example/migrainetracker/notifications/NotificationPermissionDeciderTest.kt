package com.example.migrainetracker.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionDeciderTest {

    private fun decide(
        permissionGranted: Boolean = true,
        notificationsAllowed: Boolean = true,
        alreadyAsked: Boolean = false
    ) = NotificationPermissionDecider.decide(permissionGranted, notificationsAllowed, alreadyAsked)

    @Test
    fun `alerts are deliverable when the permission is held and notifications are on`() {
        assertEquals(NotificationPermissionState.GRANTED, decide())
    }

    @Test
    fun `an untouched install can still be asked`() {
        assertEquals(
            NotificationPermissionState.REQUESTABLE,
            decide(permissionGranted = false, alreadyAsked = false)
        )
    }

    @Test
    fun `a denial is blocked rather than requestable, because Android only asks once`() {
        assertEquals(
            NotificationPermissionState.BLOCKED,
            decide(permissionGranted = false, alreadyAsked = true)
        )
    }

    @Test
    fun `notifications switched off for the app are blocked even with the permission held`() {
        assertEquals(
            NotificationPermissionState.BLOCKED,
            decide(permissionGranted = true, notificationsAllowed = false)
        )
    }

    @Test
    fun `notifications switched off outrank a pending ask, which would not fix them`() {
        assertEquals(
            NotificationPermissionState.BLOCKED,
            decide(permissionGranted = false, notificationsAllowed = false, alreadyAsked = false)
        )
    }

    @Test
    fun `granting after an earlier denial recovers, so the ask is never sticky`() {
        assertEquals(
            NotificationPermissionState.GRANTED,
            decide(permissionGranted = true, alreadyAsked = true)
        )
    }
}
