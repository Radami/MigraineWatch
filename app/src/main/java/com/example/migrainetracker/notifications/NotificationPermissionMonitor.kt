package com.example.migrainetracker.notifications

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.migrainetracker.data.preferences.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the system will actually deliver an alert, and what the user can do when it won't.
 * Distinct from the alerts preference, which records only that the user wants them.
 */
enum class NotificationPermissionState {
    GRANTED,

    /** Never asked, so the runtime dialog will still show. */
    REQUESTABLE,

    /** Nothing in-app can fix it; only the system settings screen can. */
    BLOCKED
}

/**
 * The one place that answers "can we notify?". Both the notifier and the UI read it, so the
 * screen can never claim alerts are on while [AlertNotifier] is dropping them.
 */
@Singleton
class NotificationPermissionMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences
) {
    /** The plain system check, for callers on a non-suspending path. */
    fun canPost(): Boolean = permissionGranted() && notificationsAllowed()

    /** Re-read this whenever the app comes back to the foreground: the user can revoke at any time. */
    suspend fun currentState(): NotificationPermissionState =
        NotificationPermissionDecider.decide(
            permissionGranted = permissionGranted(),
            notificationsAllowed = notificationsAllowed(),
            alreadyAsked = userPreferences.settings.first().notificationPermissionRequested
        )

    /** Below Android 13 the permission is granted at install time. */
    private fun permissionGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Survives the permission: notifications can be switched off for the app as a whole. */
    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Records that the system dialog has been shown, whatever the user answered. */
    suspend fun markRequested() {
        userPreferences.setNotificationPermissionRequested(true)
    }

    /** The system screen where a [NotificationPermissionState.BLOCKED] app can be re-enabled. */
    fun appNotificationSettingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
