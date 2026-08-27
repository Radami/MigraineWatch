package com.radami.migrainewatch.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import com.radami.migrainewatch.MainActivity
import com.radami.migrainewatch.R
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.format.formatAlertHeadline
import com.radami.migrainewatch.format.AlertTimingDetail
import com.radami.migrainewatch.format.formatAlertTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts pressure alerts to the system tray and opens the Pressure screen on tap. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionMonitor: NotificationPermissionMonitor
) {
    private companion object {
        const val TAG = "AlertNotifier"
        const val CHANNEL_ID = "pressure_alerts"
    }

    /** Safe to call repeatedly; creating an existing channel is a no-op. */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.alert_channel_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts a warning for [alert], worded for its [phase]. Returns whether it reached the tray,
     * so the caller only records an alert as delivered when it actually was — a notification
     * lost to a revoked permission should be retried, not remembered as sent.
     */
    fun notify(alert: AlertWindow, phase: AlertPhase): Boolean {
        if (!permissionMonitor.canPost()) {
            Log.w(TAG, "Notifications not permitted; skipping alert at ${alert.start}")
            return false
        }

        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(formatAlertHeadline(alert.delta, alert.direction))
            .setContentText(formatAlertTiming(alert, phase, AlertTimingDetail.WithEnd))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(detailIntent(alert))
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(alert), notification)
            true
        }.getOrElse { error ->
            // Permission can be revoked between the check above and posting.
            Log.e(TAG, "Failed to post alert notification", error)
            false
        }
    }

    /**
     * Opens the Pressure screen, which lists the event and shades it on its chart. The alert
     * itself is not passed along: that screen reads the same detection the notification came
     * from, so handing it a copy could only ever disagree with what it works out for itself.
     */
    private fun detailIntent(alert: AlertWindow): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.Tab.PRESSURE.name)
        }

        return TaskStackBuilder.create(context)
            .addNextIntentWithParentStack(intent)
            .getPendingIntent(
                notificationId(alert),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )!!
    }

    /**
     * Stable per event, so a re-posted warning replaces the old one instead of stacking up.
     * Minutes are enough resolution to separate two events; direction separates a drop from a
     * rise that start together.
     *
     * Hashed on the wire name and not the enum constant: an enum's hash code is its identity,
     * which differs between processes, and an id that moved would leave the old notification
     * in the tray beside the new one.
     */
    private fun notificationId(alert: AlertWindow): Int =
        (alert.start.epochSecond / 60).toInt() * 31 + alert.direction.wireName.hashCode()
}
