package com.radami.migrainewatch.ui.platform

import com.radami.migrainewatch.notifications.NotificationPermissionState
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS's answer to "can we notify?", read from the user notification center. iOS remembers on
 * its own whether it has asked, so unlike Android there is nothing for the app to record.
 */
class IosNotificationPermissions(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter()
) : NotificationPermissions {

    override suspend fun currentState(): NotificationPermissionState = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            continuation.resume(permissionStateFor(settings?.authorizationStatus))
        }
    }

    /** A declined prompt already reads back as denied, not as never asked. */
    override suspend fun markRequested() = Unit
}

/** The shared three states, from iOS's own. Anything not allowed or still askable is blocked. */
internal fun permissionStateFor(status: UNAuthorizationStatus?): NotificationPermissionState = when (status) {
    UNAuthorizationStatusNotDetermined -> NotificationPermissionState.REQUESTABLE

    UNAuthorizationStatusAuthorized,
    UNAuthorizationStatusProvisional,
    UNAuthorizationStatusEphemeral -> NotificationPermissionState.GRANTED

    else -> NotificationPermissionState.BLOCKED
}
