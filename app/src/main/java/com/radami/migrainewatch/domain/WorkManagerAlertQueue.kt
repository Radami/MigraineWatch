package com.radami.migrainewatch.domain

import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.radami.migrainewatch.workers.AlertNotificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * Holds pending warnings as delayed [AlertNotificationWorker]s, which re-check the forecast
 * when they wake and only then post.
 */
@Singleton
class WorkManagerAlertQueue @Inject constructor(
    private val workManager: WorkManager
) : AlertWarningQueue {

    private companion object {
        const val TAG = "AlertQueue"

        /** Tags every scheduled warning so the pending set can be read back. */
        const val WORK_TAG = "alert_notification"
    }

    override suspend fun pendingNames(): Set<String> {
        val scheduled = withContext(Dispatchers.IO) {
            workManager.getWorkInfosByTag(WORK_TAG).get()
        }

        return scheduled
            .filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
            .flatMap { info -> info.tags.filter { it.startsWith(AlertWarningName.PREFIX) } }
            .toSet()
    }

    override suspend fun cancel(name: String) {
        workManager.cancelUniqueWork(name)
    }

    override suspend fun schedule(name: String, notification: PendingAlertNotification, now: Instant) {
        val delay = (notification.notifyAt - now).coerceAtLeast(Duration.ZERO)

        val request = OneTimeWorkRequestBuilder<AlertNotificationWorker>()
            .setInitialDelay(delay.toJavaDuration())
            .setInputData(AlertNotificationWorker.inputDataFor(notification.alert))
            .addTag(WORK_TAG)
            .addTag(name)
            .build()

        Log.d(TAG, "Warning $name (${notification.phase}) scheduled in ${delay.inWholeMinutes} min")

        // REPLACE rather than KEEP: a refreshed forecast moves an event, and the warning has
        // to move with it.
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }
}
