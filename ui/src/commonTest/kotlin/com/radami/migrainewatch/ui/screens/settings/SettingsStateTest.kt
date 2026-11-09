package com.radami.migrainewatch.ui.screens.settings

import com.radami.migrainewatch.notifications.NotificationPermissionState
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Settings screen's rules, run on every platform. */
class SettingsStateTest {

    @Test
    fun wantedAndPermittedAlertsAreDelivering() {
        val state = SettingsState(notificationsEnabled = true, permission = NotificationPermissionState.GRANTED)

        assertTrue(state.alertsDelivering)
        assertFalse(state.alertsBlocked)
    }

    @Test
    fun wantedButUnpermittedAlertsAreBlocked() {
        val unpermitted = listOf(NotificationPermissionState.REQUESTABLE, NotificationPermissionState.BLOCKED)

        for (permission in unpermitted) {
            val state = SettingsState(notificationsEnabled = true, permission = permission)

            assertFalse(state.alertsDelivering, "$permission")
            assertTrue(state.alertsBlocked, "$permission")
        }
    }

    @Test
    fun alertsSwitchedOffAreNeitherDeliveringNorBlocked() {
        for (permission in NotificationPermissionState.entries) {
            val state = SettingsState(notificationsEnabled = false, permission = permission)

            assertFalse(state.alertsDelivering, "$permission")
            assertFalse(state.alertsBlocked, "$permission")
        }
    }

    @Test
    fun theFirstStateAssumesPermissionSoNoWarningFlashes() {
        assertFalse(SettingsState().alertsBlocked)
    }

    @Test
    fun anEmptyHistorySaysSo() {
        assertEquals("No entries yet", SettingsState().historySummary)
    }

    @Test
    fun aHistorySaysWhenTrackingBegan() {
        val state = SettingsState(totalEntries = 12, trackingSince = LocalDate(2026, 3, 14))

        assertEquals("Tracking since March 2026 · 12 entries", state.historySummary)
    }

    @Test
    fun aCountArrivingBeforeTheFirstDayStillShows() {
        assertEquals("12 entries", SettingsState(totalEntries = 12).historySummary)
    }
}
