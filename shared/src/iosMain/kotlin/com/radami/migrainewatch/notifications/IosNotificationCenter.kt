package com.radami.migrainewatch.notifications

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/** Statuses under which the system will actually show what the app schedules. */
private val POSTING_ALLOWED = setOf(
    UNAuthorizationStatusAuthorized,
    UNAuthorizationStatusProvisional,
    UNAuthorizationStatusEphemeral
)

/** The shortest delay a time-interval trigger accepts; a warning due now goes out after it. */
private val SHORTEST_TRIGGER: Duration = 1.seconds

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
        val delay = (notification.fireAt - clock.now()).coerceAtLeast(SHORTEST_TRIGGER)
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
            delay.toDouble(DurationUnit.SECONDS),
            repeats = false
        )
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
