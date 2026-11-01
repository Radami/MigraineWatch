package com.radami.migrainewatch.notifications

import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.domain.AlertWarningName
import com.radami.migrainewatch.domain.AlertWarningQueue
import com.radami.migrainewatch.domain.PendingAlertNotification
import com.radami.migrainewatch.format.AlertTimingDetail
import com.radami.migrainewatch.format.formatAlertHeadline
import com.radami.migrainewatch.format.formatAlertTiming
import com.radami.migrainewatch.logging.Logger
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Holds warnings as local notifications the system shows on its own, for a platform that can't
 * run the app's code when one is due (iOS). What a check at fire time would decide is settled
 * when scheduling instead, and each warning goes into history dated when it will show: the
 * reconcile reads a row from the future as still pending, and one from the past as sent.
 */
class LocalNotificationAlertQueue(
    private val center: LocalNotificationCenter,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val userPreferences: UserPreferences,
    private val clock: Clock = Clock.System
) : AlertWarningQueue {

    private companion object {
        const val TAG = "AlertQueue"

        /** The span of start times one warning name covers; see [AlertWarningName.of]. */
        val NAME_SPAN: Duration = 1.hours
    }

    override suspend fun pendingNames(): Set<String> =
        center.pendingIdentifiers().filter { it.startsWith(AlertWarningName.PREFIX) }.toSet()

    override suspend fun cancel(name: String) {
        center.cancel(name)
        forgetQueuedAhead(name, clock.now())
    }

    override suspend fun schedule(name: String, notification: PendingAlertNotification, now: Instant) {
        val settings = userPreferences.settings.first()
        val alert = notification.alert
        val zone = settings.location.zone

        val accepted = center.schedule(
            LocalNotification(
                identifier = name,
                title = formatAlertHeadline(alert.delta, alert.direction),
                body = formatAlertTiming(alert, notification.phase, AlertTimingDetail.WithEnd, zone),
                fireAt = notification.notifyAt
            )
        )

        // Left out of history, so the next reconcile offers it again once posting is allowed.
        if (!accepted) {
            Logger.w(TAG, "Notifications not permitted; warning $name not scheduled")
            return
        }

        // An earlier forecast of the same event may have started at another minute of the hour,
        // so its row has another key and would otherwise linger as a second pending warning.
        forgetQueuedAhead(name, now)
        notifiedAlertDao.insert(
            NotifiedAlert(
                startDateTime = alert.start,
                endDateTime = alert.end,
                direction = alert.direction.wireName,
                thresholdHpa = settings.alertThresholdHpa,
                notifiedDateTime = notification.notifyAt
            )
        )
    }

    private suspend fun forgetQueuedAhead(name: String, now: Instant) {
        val event = AlertWarningName.parse(name)
        if (event == null) {
            Logger.w(TAG, "Unreadable warning name $name; its history row, if any, is kept")
            return
        }

        notifiedAlertDao.deleteQueuedAhead(
            direction = event.direction.wireName,
            from = event.hourStart,
            until = event.hourStart + NAME_SPAN,
            now = now
        )
    }
}
