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
 * Deliberately pure: every rule below is a branch that can be tested without a device, a
 * database or a clock, which is where the awkward cases live.
 */
object AlertNotificationDecider {

    /** How far ahead of an event the warning is useful — early enough to act on. */
    val LEAD_TIME: Duration = Duration.ofHours(12)

    /**
     * How far back delivered warnings are read when deduplicating.
     *
     * A record that ages out of this stops suppressing its own event, so the lookback has to
     * outlast the longest event we would ever announce — a drop spread over two days is still
     * the same event on its second day, and re-announcing it is exactly the bug this guards.
     * Well inside the retention the scheduler prunes at.
     */
    val NOTIFICATION_LOOKBACK: Duration = Duration.ofDays(7)

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
            .filterNot { alert -> alreadyNotified.any { covers(it, alert) } }
            .map { alert ->
                PendingAlertNotification(alert, notifyAt(alert, now), AlertPhase.of(alert, now))
            }
            .sortedBy { it.notifyAt }
    }

    /**
     * When the warning should land.
     *
     * An event still ahead gets the full lead time, unless the forecast only just surfaced it
     * and that moment has already passed — announced late beats not at all. An event already
     * underway goes out straight away, because there is nothing left to be early for.
     */
    private fun notifyAt(alert: AlertWindow, now: Instant): Instant =
        when (AlertPhase.of(alert, now)) {
            AlertPhase.AHEAD -> maxOf(alert.start.minus(LEAD_TIME), now)
            AlertPhase.UNDERWAY -> now
        }

    /**
     * Whether a delivered warning covers [alert]. Direction matters: a drop and a rise
     * starting at the same time are two different things to warn about.
     *
     * Note this ignores the threshold each was sent at. Once told about an event the user has
     * been told, so raising and then lowering sensitivity does not re-announce it.
     */
    fun covers(notified: NotifiedAlert, alert: AlertWindow): Boolean {
        // A row whose direction this version cannot read matches nothing. Re-announcing an
        // event is a far smaller failure than silently suppressing one on a direction we
        // guessed at, so an unknown spelling is never treated as a match.
        val notifiedDirection = PressureDirection.ofWireName(notified.direction) ?: return false

        return isSameEvent(
            notifiedDirection, notified.startDateTime, notified.endDateTime,
            alert.direction, alert.start, alert.end
        )
    }

    /**
     * Whether two forecasts describe the same event. Shared with the worker so a warning is
     * matched against the live forecast by the same rule that matched it against history —
     * two rules would eventually disagree about which event a notification belongs to.
     */
    fun isSameEvent(alert: AlertWindow, other: AlertWindow): Boolean =
        isSameEvent(
            alert.direction, alert.start, alert.end,
            other.direction, other.start, other.end
        )

    /**
     * Same direction, and sharing at least a moment.
     *
     * Overlap rather than nearness of starts: a refreshed forecast stretches an event or moves
     * it an hour without it becoming a different one, and it keeps overlapping through that,
     * while two events sharing no time at all stay distinct however close their starts fall.
     * It is also how [AlertDetector] itself decides what counts as one event, so the two
     * cannot disagree.
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
