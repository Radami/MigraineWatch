package com.radami.migrainewatch.ui.platform

import com.radami.migrainewatch.notifications.NotificationPermissionState
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertEquals

/** How iOS's own notification states map onto the three the screens understand. */
class IosNotificationPermissionsTest {

    @Test
    fun neverAskedCanStillBeAsked() {
        assertEquals(NotificationPermissionState.REQUESTABLE, permissionStateFor(UNAuthorizationStatusNotDetermined))
    }

    @Test
    fun everyKindOfAllowedCanPost() {
        val allowed = listOf(
            UNAuthorizationStatusAuthorized,
            UNAuthorizationStatusProvisional,
            UNAuthorizationStatusEphemeral
        )

        for (status in allowed) {
            assertEquals(NotificationPermissionState.GRANTED, permissionStateFor(status), "status $status")
        }
    }

    @Test
    fun deniedCanOnlyBeFixedInSettings() {
        assertEquals(NotificationPermissionState.BLOCKED, permissionStateFor(UNAuthorizationStatusDenied))
    }

    @Test
    fun noSettingsAtAllIsBlocked() {
        assertEquals(NotificationPermissionState.BLOCKED, permissionStateFor(null))
    }
}
