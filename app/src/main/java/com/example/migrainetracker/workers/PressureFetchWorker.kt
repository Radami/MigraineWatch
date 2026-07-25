package com.example.migrainetracker.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.migrainetracker.data.repository.PressureRepository
import com.example.migrainetracker.domain.AlertNotificationScheduler
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class PressureFetchWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val pressureRepository: PressureRepository,
    private val alertScheduler: AlertNotificationScheduler
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            pressureRepository.refresh()

            // A new forecast can add, move or remove events, so the pending warnings are
            // rebuilt from it every time.
            alertScheduler.reconcile()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "pressure_fetch"
        private const val RUN_NOW_WORK_NAME = "pressure_fetch_now"

        /**
         * How often the forecast is re-fetched. Open-Meteo publishes hourly, so this is as
         * fine-grained as the data gets; widening it to 3 or 6 hours costs little once alerts
         * are scheduled ahead of an event rather than discovered by polling, and saves the
         * radio waking up 24 times a day. WorkManager will not go below 15 minutes.
         */
        const val REFRESH_INTERVAL_HOURS = 1L

        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<PressureFetchWorker>(
                REFRESH_INTERVAL_HOURS,
                TimeUnit.HOURS
            ).build()
            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Fetches once, now. The periodic work only starts a full interval after being
         * enqueued, so this is what makes a freshly opened app reconcile its warnings.
         */
        fun runNow(workManager: WorkManager) {
            workManager.enqueueUniqueWork(
                RUN_NOW_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PressureFetchWorker>().build()
            )
        }
    }
}
