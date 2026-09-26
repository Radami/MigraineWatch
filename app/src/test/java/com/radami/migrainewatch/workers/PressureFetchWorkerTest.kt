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
 * The repository reports failure instead of throwing, so the worker's try/catch can't see it.
 * Without an explicit check, a failed fetch would report success to WorkManager and the app
 * would sit stale for a full interval instead of retrying on backoff.
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

    /** Matches any instant: `reconcile` defaults its clock arg, so a bare stub would never match. */
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
     * Reconcile rebuilds the pending set from stored data and the clock, which moves even when
     * the fetch fails. Skipping it would let an offline device hold warnings for weather that's over.
     */
    @Test
    fun `a failed fetch still prunes the pending warnings`() {
        coEvery { repository.refresh() } returns RefreshState.Failed
        reconcileSucceeds()

        runWorker()

        coVerify(exactly = 1) { scheduler.reconcile(any()) }
    }

    /**
     * A fetch superseded by a move reports [RefreshState.InFlight] since nothing stored yet.
     * Reporting success would delay the next check a full interval.
     */
    @Test
    fun `a superseded fetch is retried rather than reported as a run that happened`() {
        coEvery { repository.refresh() } returns RefreshState.InFlight
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    /** No location isn't a failed fetch; retrying won't make one appear, so report success. */
    @Test
    fun `no location set is a finished run rather than one to retry`() {
        coEvery { repository.refresh() } returns RefreshState.NoLocation
        reconcileSucceeds()

        assertEquals(ListenableWorker.Result.success(), runWorker())
    }
}
