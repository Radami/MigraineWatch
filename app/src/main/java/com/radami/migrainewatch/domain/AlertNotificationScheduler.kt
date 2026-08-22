package com.radami.migrainewatch.domain

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.workers.AlertNotificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What one reconcile did, so a caller can report it instead of leaving it to logcat. */
sealed interface ReconcileResult {

    /** @param pending warnings now queued. @param cancelled warnings that stopped qualifying. */
    data class Success(val pending: Int, val cancelled: Int) : ReconcileResult

    data class Failed(val cause: Throwable) : ReconcileResult
}

/**
 * Keeps the set of scheduled pressure warnings in step with the forecast and the user's
 * settings.
 *
 * This is a reconcile rather than an append: every run recomputes the complete set of
 * warnings that should be pending and makes the queue match it. That is what lets one method
 * serve three callers — a refreshed forecast, a changed sensitivity, and app start — and why
 * an event that drops out of the forecast, or stops clearing a raised threshold, takes its
 * pending notification with it.
 */
@Singleton
class AlertNotificationScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val alertUseCase: PressureAlertUseCase,
    private val notifiedAlertDao: NotifiedAlertDao,
    private val userPreferences: UserPreferences
) {
    private companion object {
        const val TAG = "AlertScheduler"

        /** Tags every scheduled warning so the pending set can be read back. */
        const val WORK_TAG = "alert_notification"

        const val WORK_NAME_PREFIX = "alert_notification_"

        /** Delivered warnings are pruned beyond this; nothing that old can still be forecast. */
        val HISTORY_RETENTION: Duration = Duration.ofDays(30)

        /** Room for a burst of reconciles so [results] never drops one it could have reported. */
        const val RESULT_BUFFER = 8
    }

    private val _results = MutableSharedFlow<ReconcileResult>(extraBufferCapacity = RESULT_BUFFER)

    /**
     * The outcome of every reconcile, whoever triggered it. The debug settings section listens
     * here so a fetch, a sensitivity change and the hourly worker all report themselves.
     */
    val results: SharedFlow<ReconcileResult> = _results.asSharedFlow()

    suspend fun reconcile(now: Instant = Instant.now()): ReconcileResult {
        val result = runCatching {
            val settings = userPreferences.settings.first()
            val alerts = alertUseCase.currentAlerts(now)
            val alreadyNotified = notifiedAlertDao.getNotifiedSince(
                now.minus(AlertNotificationDecider.NOTIFICATION_LOOKBACK)
            )

            val pending = AlertNotificationDecider.decide(
                alerts = alerts,
                alreadyNotified = alreadyNotified,
                notificationsEnabled = settings.notificationsEnabled,
                now = now
            )

            val wanted = pending.associateBy { workName(it) }
            val cancelled = cancelWarningsMissingFrom(wanted.keys)
            wanted.forEach { (name, notification) -> enqueue(name, notification, now) }

            notifiedAlertDao.deleteOlderThan(now.minus(HISTORY_RETENTION))
            ReconcileResult.Success(pending = wanted.size, cancelled = cancelled)
        }.getOrElse { error ->
            // Never let scheduling take down the caller: a failed reconcile costs one cycle,
            // and the next fetch runs it again.
            Log.e(TAG, "Failed to reconcile alert notifications", error)
            ReconcileResult.Failed(error)
        }

        _results.tryEmit(result)
        return result
    }

    /** @return how many warnings were cancelled. */
    private suspend fun cancelWarningsMissingFrom(wanted: Set<String>): Int {
        val scheduled = withContext(Dispatchers.IO) {
            workManager.getWorkInfosByTag(WORK_TAG).get()
        }

        val stale = scheduled
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .flatMap { info -> info.tags.filter { it.startsWith(WORK_NAME_PREFIX) } }
            .distinct()
            .filterNot { it in wanted }

        stale.forEach { staleName ->
            Log.d(TAG, "Cancelling warning that no longer qualifies: $staleName")
            workManager.cancelUniqueWork(staleName)
        }

        return stale.size
    }

    private fun enqueue(name: String, notification: PendingAlertNotification, now: Instant) {
        val delay = Duration.between(now, notification.notifyAt).coerceAtLeast(Duration.ZERO)

        val request = OneTimeWorkRequestBuilder<AlertNotificationWorker>()
            .setInitialDelay(delay)
            .setInputData(AlertNotificationWorker.inputDataFor(notification.alert))
            .addTag(WORK_TAG)
            .addTag(name)
            .build()

        Log.d(TAG, "Warning $name (${notification.phase}) scheduled in ${delay.toMinutes()} min")

        // REPLACE rather than KEEP: a refreshed forecast moves an event, and the warning has
        // to move with it.
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Identifies a warning by the event it describes. Hours, not seconds, so the small drift
     * between two forecasts maps to the same unique work rather than piling up duplicates.
     */
    private fun workName(notification: PendingAlertNotification): String {
        val alert = notification.alert
        return "$WORK_NAME_PREFIX${alert.direction.wireName}_${alert.start.epochSecond / 3600}"
    }
}
