package com.radami.migrainewatch.ui.screens.settings

import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.notifications.NotificationPermissionState
import kotlinx.datetime.LocalDate

/** What the Settings screen shows. */
data class SettingsState(
    val alertSensitivity: AlertSensitivity = AlertSensitivity.Default,
    /** What the user asked for, which is not the same as what the system will deliver. */
    val notificationsEnabled: Boolean = true,
    // Assumed granted until the first check answers, so the warning row does not flash on
    // every entry to the screen.
    val permission: NotificationPermissionState = NotificationPermissionState.GRANTED,
    val totalEntries: Int = 0,
    /** The first logged day, or null while nothing is logged. */
    val trackingSince: LocalDate? = null
) {
    /** The switch tracks delivery: on means an alert would actually arrive. */
    val alertsDelivering: Boolean
        get() = notificationsEnabled && permission == NotificationPermissionState.GRANTED

    /** Wanted but undeliverable — the silent failure the warning row exists to explain. */
    val alertsBlocked: Boolean
        get() = notificationsEnabled && permission != NotificationPermissionState.GRANTED

    /** The line under the title: how long the user has been logging and how much. */
    val historySummary: String
        get() {
            if (totalEntries == 0) return "No entries yet"

            // The first day is read once, so a count can arrive before it does.
            if (trackingSince == null) return "$totalEntries entries"

            return "Tracking since ${AppDateFormats.MONTH_AND_YEAR.format(trackingSince)} · $totalEntries entries"
        }
}
