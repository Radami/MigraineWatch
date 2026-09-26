package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.NotifiedAlert
import java.time.Duration
import java.time.Instant

/** An alert the user should be told about, when to tell them, and what it can claim. */
data class PendingAlertNotification(
    val alert: AlertWindow,
    val notifyAt: Instant,
    val phase: AlertPhase
)

/**
 * Decides which pressure events deserve a notification and when it should land.
 *
 * Deliberately pure so every rule is testable without a device, database, or clock.
 */
object AlertNotificationDecider {

    /** How far ahead of an event the warning is useful — early enough to act on. */
    val LEAD_TIME: Duration = Duration.ofHours(12)

    /**
     * How far back delivered warnings are read when deduplicating. Must outlast the longest
     * event we'd announce, or a multi-day event gets re-announced on its later days.
     */
    val NOTIFICATION_LOOKBACK: Duration = Duration.ofDays(7)

    /**
     * @param alerts events currently in the forecast, from [PressureAlertUseCase].
     * @param alreadyNotified events the user has been told about, recent ones suffice.
     * @return the complete set to schedule, ordered by fire time. Anything scheduled but
     *   missing here has stopped qualifying and gets cancelled.
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
            .filterNot { alert -> alreadyNotified.any { covers(it, alert) } }
            .map { alert ->
                PendingAlertNotification(alert, notifyAt(alert, now), AlertPhase.of(alert, now))
            }
            .sortedBy { it.notifyAt }
    }

    /**
     * When the warning should land. An event still ahead gets the full lead time unless that
     * moment already passed (late beats never). An underway event fires immediately.
     */
    private fun notifyAt(alert: AlertWindow, now: Instant): Instant =
        when (AlertPhase.of(alert, now)) {
            AlertPhase.AHEAD -> maxOf(alert.start.minus(LEAD_TIME), now)
            AlertPhase.UNDERWAY -> now
        }

    /**
     * Whether a delivered warning covers [alert]. Direction matters. Ignores the threshold each
     * was sent at, so a sensitivity change doesn't re-announce an already-told event.
     */
    fun covers(notified: NotifiedAlert, alert: AlertWindow): Boolean {
        // An unreadable direction matches nothing: re-announcing is safer than a false match.
        val notifiedDirection = PressureDirection.ofWireName(notified.direction) ?: return false

        return isSameEvent(
            notifiedDirection, notified.startDateTime, notified.endDateTime,
            alert.direction, alert.start, alert.end
        )
    }

    /**
     * Whether two forecasts describe the same event. Shared with the worker so history and the
     * live forecast are matched by the same rule.
     */
    fun isSameEvent(alert: AlertWindow, other: AlertWindow): Boolean =
        isSameEvent(
            alert.direction, alert.start, alert.end,
            other.direction, other.start, other.end
        )

    /**
     * Same direction and overlapping in time (not nearness of start): a refreshed forecast can
     * stretch or shift an event without it becoming a new one. Matches how [AlertDetector]
     * itself groups events.
     */
    private fun isSameEvent(
        direction: PressureDirection,
        start: Instant,
        end: Instant,
        otherDirection: PressureDirection,
        otherStart: Instant,
        otherEnd: Instant
    ): Boolean {
        if (direction != otherDirection) return false
        return !start.isAfter(otherEnd) && !otherStart.isAfter(end)
    }
}
