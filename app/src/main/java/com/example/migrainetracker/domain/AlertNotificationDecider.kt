package com.example.migrainetracker.domain

import com.example.migrainetracker.data.model.NotifiedAlert
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** An alert the user should be told about, and when to tell them. */
data class PendingAlertNotification(
    val alert: AlertWindow,
    val notifyAt: Instant
)

/**
 * Decides which pressure events deserve a notification and when it should land.
 *
 * Deliberately pure: every rule below is a branch that can be tested without a device, a
 * database or a clock, which is where the awkward cases live.
 */
object AlertNotificationDecider {

    /** How far ahead of an event the warning is useful — early enough to act on. */
    val LEAD_TIME: Duration = Duration.ofHours(12)

    /**
     * A refreshed forecast nudges an event's start around without it becoming a different
     * event, so "already notified" is matched within this tolerance rather than exactly.
     */
    val SAME_EVENT_TOLERANCE: Duration = Duration.ofHours(6)

    /**
     * @param alerts events currently in the forecast, from [PressureAlertUseCase].
     * @param alreadyNotified events the user has been told about, recent ones suffice.
     * @return what should be scheduled, ordered by when it should fire. Callers treat this as
     *   the complete set: anything scheduled but missing here has stopped qualifying and is
     *   cancelled. That is what makes a sensitivity change take effect in both directions —
     *   events that no longer clear the threshold disappear, newly qualifying ones appear.
     */
    fun decide(
        alerts: List<AlertWindow>,
        alreadyNotified: List<NotifiedAlert>,
        notificationsEnabled: Boolean,
        now: Instant
    ): List<PendingAlertNotification> {
        if (!notificationsEnabled) return emptyList()

        return alerts
            // An event that has already finished is history, not a warning.
            .filter { it.end.isAfter(now) }
            .filterNot { alert -> alreadyNotified.any { it.matches(alert) } }
            .map { alert ->
                // Events found late — a forecast can surface one inside its own lead time —
                // are announced immediately rather than not at all.
                val lead = alert.start.minus(LEAD_TIME)
                PendingAlertNotification(alert, maxOf(lead, now))
            }
            .sortedBy { it.notifyAt }
    }

    /**
     * Whether a delivered notification covers [alert]. Direction matters: a drop and a rise
     * starting at the same time are two different things to warn about.
     *
     * Note this ignores the threshold each was sent at. Once told about an event the user has
     * been told, so raising and then lowering sensitivity does not re-announce it.
     */
    private fun NotifiedAlert.matches(alert: AlertWindow): Boolean {
        if (direction != alert.direction) return false
        val gapMillis = abs(startDateTime.toEpochMilli() - alert.start.toEpochMilli())
        return gapMillis <= SAME_EVENT_TOLERANCE.toMillis()
    }
}
