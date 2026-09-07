package com.radami.migrainewatch.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertNotificationScheduler
import com.radami.migrainewatch.domain.ReconcileResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the hourly fetch does with the outcome the repository hands it.
 *
 * The repository reports a failure rather than throwing one, so the worker's own try/catch
 * cannot see it: without an explicit check a fetch that never reached the network is reported
 * to WorkManager as a run that happened, and the app sits stale for a full interval instead of
 * being retried on backoff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PressureFetchWorkerTest {

    private val repository = mockk<PressureRepository>()
    private val scheduler = mockk<AlertNotificationScheduler>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun runWorker(): ListenableWorker.Result {
        val worker = TestListenableWorkerBuilder<PressureFetchWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = PressureFetchWorker(appContext, workerParameters, repository, scheduler)
            })
            .build()

        return runBlocking { worker.doWork() }
    }

    /**
     * Matched on any instant rather than none: `reconcile` defaults its clock argument, so a
     * stub written without one pins the moment the stub was recorded and never matches the call.
     */
    private fun reconcileSucceeds() {
        coEvery { scheduler.reconcile(any()) } returns ReconcileResult.Success(pending = 0, cancelled = 0)
    }

    @Test
    fun `a stored series reconciles the pending warnings and reports success`() {
        coEvery { repository.refresh() } returns RefreshState.Updated
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.success(), runWorker())
        coVerify(exactly = 1) { scheduler.reconcile(any()) }
    }

    @Test
    fun `a failed fetch is retried`() {
        coEvery { repository.refresh() } returns RefreshState.Failed

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    /**
     * And does not reconcile: the warnings would be rebuilt from the series the fetch failed to
     * replace, which is the stale one this run existed to move past.
     */
    @Test
    fun `a failed fetch leaves the pending warnings alone`() {
        coEvery { repository.refresh() } returns RefreshState.Failed

        runWorker()

        coVerify(exactly = 0) { scheduler.reconcile(any()) }
    }

    /**
     * A fetch superseded by a move comes back as [RefreshState.InFlight]: the replacement is
     * still running, so nothing has been stored yet. Reconciling here would queue warnings off
     * the city the user has just left, and reporting success would leave them queued for a full
     * interval before anything looked again.
     */
    @Test
    fun `a superseded fetch is retried rather than reported as a run that happened`() {
        coEvery { repository.refresh() } returns RefreshState.InFlight

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        coVerify(exactly = 0) { scheduler.reconcile(any()) }
    }

    /**
     * Nothing to fetch for is not a fetch that went wrong, and retrying on backoff would not
     * make a location appear. The run is over; reconciling still clears anything left queued.
     */
    @Test
    fun `no location set is a finished run rather than one to retry`() {
        coEvery { repository.refresh() } returns RefreshState.NoLocation
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.success(), runWorker())
    }
}
