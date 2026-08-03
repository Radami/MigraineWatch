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
import com.radami.migrainewatch.AlertDetailActivity
import com.radami.migrainewatch.R
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.format.formatHpa
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Posts pressure alerts to the system tray and opens the matching detail screen on tap. */
@Singleton
class AlertNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionMonitor: NotificationPermissionMonitor
) {
    private companion object {
        const val TAG = "AlertNotifier"
        const val CHANNEL_ID = "pressure_alerts"
        const val DROP_DIRECTION = "drop"
    }

    private val timeFormatter = AppDateFormats.WEEKDAY_AND_TIME

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

        val directionLabel =
            if (alert.direction == DROP_DIRECTION) "pressure drop" else "pressure rise"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("${formatHpa(alert.delta)} hPa $directionLabel")
            .setContentText(timingLabel(alert, phase))
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
     * An event still ahead is described by when it arrives. One already running is described by
     * when it ends, because "starts" would be describing the past — the user is in it.
     */
    private fun timingLabel(alert: AlertWindow, phase: AlertPhase): String {
        val zone = ZoneId.systemDefault()
        val startLabel = timeFormatter.format(alert.start.atZone(zone))

        return when (phase) {
            AlertPhase.AHEAD -> "Starts $startLabel"
            AlertPhase.UNDERWAY ->
                "Underway since $startLabel · eases ${timeFormatter.format(alert.end.atZone(zone))}"
        }
    }

    /** Opens the alert's detail screen with Today underneath it, as if navigated to in-app. */
    private fun detailIntent(alert: AlertWindow): PendingIntent {
        val intent = Intent(context, AlertDetailActivity::class.java).apply {
            putExtra("startEpoch", alert.start.epochSecond)
            putExtra("endEpoch", alert.end.epochSecond)
            putExtra("delta", alert.delta)
            putExtra("direction", alert.direction)
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
     */
    private fun notificationId(alert: AlertWindow): Int =
        (alert.start.epochSecond / 60).toInt() * 31 + alert.direction.hashCode()
}
