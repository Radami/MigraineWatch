package com.radami.migrainewatch.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.radami.migrainewatch.logging.Logger
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private const val TAG = "NotificationSettings"

@Composable
actual fun rememberNotificationPermissionRequest(onFinished: () -> Unit): () -> Unit {
    val finished by rememberUpdatedState(onFinished)

    return remember {
        {
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound
            ) { _, error ->
                // A failed request leaves the permission as it was, which the re-read then shows.
                if (error != null) Logger.w(TAG, "Notification request failed: ${error.localizedDescription}")

                // The answer arrives off the main thread; the screen's state lives on it.
                dispatch_async(dispatch_get_main_queue()) { finished() }
            }
        }
    }
}

@Composable
actual fun rememberOpenNotificationSettings(): () -> Unit = remember {
    {
        val url = checkNotNull(NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString)) {
            "The system's notification-settings link is not a URL"
        }
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }
}

actual val systemName: String = "iOS"
