package com.radami.migrainewatch.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertNotificationScheduler
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

    /**
     * The repository reports failure as a value rather than a thrown exception, so it must be
     * read explicitly or a failed run looks like a success.
     */
    override suspend fun doWork(): Result {
        return try {
            val outcome = pressureRepository.refresh()

            // Reconcile always runs, even on a failed fetch: the clock has moved regardless,
            // and skipping it would leave warnings for events that already finished.
            alertScheduler.reconcile()

            when (outcome) {
                // This run did not bring the app up to date, so retry on WorkManager's backoff
                // instead of waiting a full interval.
                RefreshState.Failed, RefreshState.InFlight -> Result.retry()

                RefreshState.Updated, RefreshState.NoReadings, RefreshState.NoLocation ->
                    Result.success()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "pressure_fetch"
        private const val RUN_NOW_WORK_NAME = "pressure_fetch_now"

        /** How often the forecast is re-fetched. Open-Meteo publishes hourly, so this is as fine as the data gets. */
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
