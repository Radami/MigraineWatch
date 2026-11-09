package com.radami.migrainewatch.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android's answer to "can we notify?", and the one place that gives it. Both the notifier and
 * the UI read it, so the screen can never claim alerts are on while [AlertNotifier] is dropping
 * them.
 */
@Singleton
class AndroidNotificationPermissions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userPreferences: UserPreferences
) : NotificationPermissions {
    /** The plain system check, for callers on a non-suspending path. */
    fun canPost(): Boolean = permissionGranted() && notificationsAllowed()

    override suspend fun currentState(): NotificationPermissionState =
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

    override suspend fun markRequested() {
        userPreferences.setNotificationPermissionRequested(true)
    }
}
