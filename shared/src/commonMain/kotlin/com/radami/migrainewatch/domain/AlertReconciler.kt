package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.model.toDeliveredWarning
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.logging.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What one reconcile did, so a caller can report it instead of leaving it to the log. */
sealed interface ReconcileResult {

    /** @param pending warnings now queued. @param cancelled warnings that stopped qualifying. */
    data class Success(val pending: Int, val cancelled: Int) : ReconcileResult

    data class Failed(val cause: Throwable) : ReconcileResult
}

/**
 * Where a platform keeps warnings until they are due: WorkManager on Android, the notification
 * center on iOS. [AlertReconciler] decides what should be queued; this only holds it.
 */
interface AlertWarningQueue {

    /** The names of warnings queued and still to fire. */
    suspend fun pendingNames(): Set<String>

    suspend fun cancel(name: String)

    /** Queues [notification] under [name], replacing any warning already queued under it. */
    suspend fun schedule(name: String, notification: PendingAlertNotification, now: Instant)
}

/** The event a warning name stands for: its direction and the hour it starts in. */
data class WarningEvent(val direction: PressureDirection, val hourStart: Instant)

/** How a queued warning is named, so a refreshed forecast replaces it instead of adding a second. */
object AlertWarningName {

    const val PREFIX = "alert_notification_"

    private const val SEPARATOR = '_'
    private const val SECONDS_PER_HOUR = 3600

    /**
     * Identifies a warning by the event it describes. Hours, not seconds, so the small drift
     * between two forecasts maps to the same name rather than piling up duplicates.
     */
    fun of(notification: PendingAlertNotification): String {
        val alert = notification.alert
        return "$PREFIX${alert.direction.wireName}$SEPARATOR${alert.start.epochSeconds / SECONDS_PER_HOUR}"
    }

    /**
     * The event [name] was made for, so a queue that only knows a warning by name can find
     * the history behind it. Null for a name this build can't read.
     */
    fun parse(name: String): WarningEvent? {
        if (!name.startsWith(PREFIX)) return null

        val rest = name.removePrefix(PREFIX)
        val direction = PressureDirection.ofWireName(rest.substringBeforeLast(SEPARATOR)) ?: return null
        val hour = rest.substringAfterLast(SEPARATOR, missingDelimiterValue = "").toLongOrNull() ?: return null

        return WarningEvent(direction, Instant.fromEpochSeconds(hour * SECONDS_PER_HOUR))
    }
}

/**
 * Keeps the set of queued pressure warnings in step with the forecast and the user's settings.
 *
 * A reconcile rather than an append: every run recomputes the full set of warnings that should
 * be pending and makes the queue match it, so one method serves all callers correctly.
 */
class AlertReconciler(
    private val queue: AlertWarningQueue,
    private val alertUseCase: PressureAlertUseCase,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val userPreferences: UserPreferences
) {
    private companion object {
        const val TAG = "AlertReconciler"

        /** Delivered warnings are pruned beyond this; nothing that old can still be forecast. */
        val HISTORY_RETENTION: Duration = 30.days

        /** Room for a burst of reconciles so [results] never drops one it could have reported. */
        const val RESULT_BUFFER = 8
    }

    private val _results = MutableSharedFlow<ReconcileResult>(extraBufferCapacity = RESULT_BUFFER)

    /** The outcome of every reconcile, whoever triggered it. Debug settings listen here. */
    val results: SharedFlow<ReconcileResult> = _results.asSharedFlow()

    suspend fun reconcile(now: Instant = Clock.System.now()): ReconcileResult {
        val result = runCatching {
            val settings = userPreferences.settings.first()
            val alerts = alertUseCase.currentAlerts(now)
            // A row dated later than now is a warning queued ahead, as iOS records them, not
            // one delivered: counting it would cancel the very warning it stands for.
            val alreadyNotified = notifiedAlertDao
                .getNotifiedSince(now - AlertNotificationDecider.NOTIFICATION_LOOKBACK)
                .filter { it.notifiedDateTime <= now }
                .mapNotNull { it.toDeliveredWarning() }

            val pending = AlertNotificationDecider.decide(
                alerts = alerts,
                alreadyNotified = alreadyNotified,
                notificationsEnabled = settings.notificationsEnabled,
                now = now
            )

            val wanted = pending.associateBy { AlertWarningName.of(it) }
            val cancelled = cancelWarningsMissingFrom(wanted.keys)
            wanted.forEach { (name, notification) -> queue.schedule(name, notification, now) }

            notifiedAlertDao.deleteOlderThan(now - HISTORY_RETENTION)
            ReconcileResult.Success(pending = wanted.size, cancelled = cancelled)
        }.getOrElse { error ->
            // Never let scheduling take down the caller; the next fetch retries.
            Logger.e(TAG, "Failed to reconcile alert notifications", error)
            ReconcileResult.Failed(error)
        }

        _results.tryEmit(result)
        return result
    }

    /** @return how many warnings were cancelled. */
    private suspend fun cancelWarningsMissingFrom(wanted: Set<String>): Int {
        val stale = queue.pendingNames() - wanted

        stale.forEach { staleName ->
            Logger.d(TAG, "Cancelling warning that no longer qualifies: $staleName")
            queue.cancel(staleName)
        }

        return stale.size
    }
}
