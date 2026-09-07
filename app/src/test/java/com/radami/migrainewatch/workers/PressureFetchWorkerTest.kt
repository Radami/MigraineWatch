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
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    /**
     * And still prunes the queue on its way out.
     *
     * A reconcile is not only about new data: it rebuilds the pending set from the stored series
     * and the clock, and the clock has moved even when the fetch brought nothing back. Skipped
     * here, a device that spends a day offline goes on holding warnings for weather that is
     * already over.
     */
    @Test
    fun `a failed fetch still prunes the pending warnings`() {
        coEvery { repository.refresh() } returns RefreshState.Failed
        reconcileSucceeds()

        runWorker()

        coVerify(exactly = 1) { scheduler.reconcile(any()) }
    }

    /**
     * A fetch superseded by a move comes back as [RefreshState.InFlight]: the replacement is
     * still running, so nothing has been stored yet. Reporting success would leave the app a
     * full interval behind before anything looked again — and the reconcile that runs here is
     * corrected by AlertReconcileMonitor as soon as the replacement lands.
     */
    @Test
    fun `a superseded fetch is retried rather than reported as a run that happened`() {
        coEvery { repository.refresh() } returns RefreshState.InFlight
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.retry(), runWorker())
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
