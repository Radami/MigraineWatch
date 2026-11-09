package com.radami.migrainewatch.ui.platform

import com.radami.migrainewatch.notifications.NotificationPermissionState

/**
 * What the system allows the app to post, as each platform answers it. The screens read it to
 * explain alerts that were asked for but can't arrive.
 */
interface NotificationPermissions {

    /** Re-read whenever the app comes back to the foreground: the user can revoke at any time. */
    suspend fun currentState(): NotificationPermissionState

    /** Records that the system dialog has been shown, whatever the user answered. */
    suspend fun markRequested()
}
