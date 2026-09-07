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
     * The outcome has to be read rather than inferred from whether anything was thrown: the
     * repository reports a failure as a value, so a run that never reached the network would
     * otherwise look like a run that had worked and wait a full interval for its next chance.
     */
    override suspend fun doWork(): Result {
        return try {
            val outcome = pressureRepository.refresh()

            // Whatever the fetch did. A reconcile rebuilds the pending set from the stored
            // series *and the clock*, and the clock has moved even when the series has not: an
            // event that has since finished loses its warning on a run that fetched nothing at
            // all. Skipping it on a failure is how a device that spends a day offline stops
            // pruning warnings for weather that is already over.
            alertScheduler.reconcile()

            when (outcome) {
                // Nothing was stored, or a fetch for a new location is still running. Either
                // way this run has not brought the app up to date, so it goes back on
                // WorkManager's backoff rather than waiting a whole interval for its next turn.
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
