package com.radami.migrainewatch.notifications

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.Instant

/** Statuses under which the system will actually show what the app schedules. */
private val POSTING_ALLOWED = setOf(
    UNAuthorizationStatusAuthorized,
    UNAuthorizationStatusProvisional,
    UNAuthorizationStatusEphemeral
)

/** The shortest delay a time-interval trigger accepts; a warning due now goes out after it. */
private val SHORTEST_TRIGGER: Duration = 1.seconds

/** Fire dates are written in UTC, so the trigger names one instant whatever the device zone. */
private val UTC: NSTimeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)

private val FIRE_DATE_UNITS = NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
    NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond

/**
 * When the center should show a warning due at [fireAt]. A calendar date, so the trigger names
 * one exact instant and reads back as scheduled, unlike a relative timer. A date already past
 * never fires, though, so a warning due now goes out on the shortest timer instead.
 */
internal fun triggerFor(fireAt: Instant, now: Instant): UNNotificationTrigger {
    if (fireAt <= now + SHORTEST_TRIGGER) {
        return UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            SHORTEST_TRIGGER.toDouble(DurationUnit.SECONDS),
            repeats = false
        )
    }

    return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(fireDateComponents(fireAt), repeats = false)
}

/** [fireAt] to the second, as Gregorian date components in UTC. */
internal fun fireDateComponents(fireAt: Instant): NSDateComponents {
    val calendar = requireNotNull(NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)) {
        "The Gregorian calendar is unavailable"
    }
    calendar.timeZone = UTC

    val date = NSDate.dateWithTimeIntervalSince1970(fireAt.epochSeconds.toDouble())
    return calendar.components(FIRE_DATE_UNITS, fromDate = date).apply {
        setCalendar(calendar)
        setTimeZone(UTC)
    }
}

/**
 * Schedules warnings with the user notification center, which shows them on its own when due.
 * Needs an app bundle, so it is exercised in the app rather than in unit tests.
 */
class IosNotificationCenter(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
    private val clock: Clock = Clock.System
) : LocalNotificationCenter {

    override suspend fun pendingIdentifiers(): Set<String> = suspendCancellableCoroutine { continuation ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            val identifiers = requests.orEmpty().filterIsInstance<UNNotificationRequest>().map { it.identifier }
            continuation.resume(identifiers.toSet())
        }
    }

    override suspend fun schedule(notification: LocalNotification): Boolean {
        // The center takes requests it will never show while not permitted, so ask first rather
        // than let the warning be recorded as sent.
        if (!isPostingAllowed()) return false

        val content = UNMutableNotificationContent().apply {
            setTitle(notification.title)
            setBody(notification.body)
            setSound(UNNotificationSound.defaultSound())
        }
        val trigger = triggerFor(notification.fireAt, clock.now())
        val request = UNNotificationRequest.requestWithIdentifier(notification.identifier, content, trigger)

        // A failure here is not a refusal: it is raised so the reconcile reports it as failed.
        return suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(request) { error ->
                if (error == null) {
                    continuation.resume(true)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("Scheduling ${notification.identifier} failed: ${error.localizedDescription}")
                    )
                }
            }
        }
    }

    override suspend fun cancel(identifier: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(identifier))
    }

    private suspend fun isPostingAllowed(): Boolean = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            continuation.resume(settings?.authorizationStatus in POSTING_ALLOWED)
        }
    }
}
