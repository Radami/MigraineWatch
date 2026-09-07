package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * That a series which lands takes the queued warnings with it.
 *
 * The hourly worker reconciles after its own fetch, but it is not the only thing that fetches:
 * a change of location refetches everything from inside the repository, and nothing was
 * watching for it. The queued warnings went on describing the city the user had left until the
 * worker next happened to fire — up to an hour of notifications about the wrong continent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertReconcileMonitorTest {

    private val repository = mockk<PressureRepository>()
    private val scheduler = mockk<AlertNotificationScheduler>()

    private val refreshState = MutableStateFlow(RefreshState.InFlight)

    // Unconfined so a published state is acted on inline: the test can then say "a refresh has
    // landed" and read what happened without waiting on a clock.
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @Before
    fun setup() {
        every { repository.refreshState } returns refreshState
        coEvery { scheduler.reconcile(any()) } returns
            ReconcileResult.Success(pending = 0, cancelled = 0)

        AlertReconcileMonitor(repository, scheduler, scope).start()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a series that lands rebuilds the pending warnings`() = runTest {
        refreshState.value = RefreshState.Updated

        coVerify(exactly = 1) { scheduler.reconcile(any()) }
    }

    /**
     * Nothing was stored, so the queue still matches what is there. Rebuilding it would recompute
     * the same set from the very series the failed fetch was trying to replace.
     */
    @Test
    fun `a failed fetch leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.Failed

        coVerify(exactly = 0) { scheduler.reconcile(any()) }
    }

    @Test
    fun `a fetch still out leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.InFlight

        coVerify(exactly = 0) { scheduler.reconcile(any()) }
    }

    /** A location the fetch found nothing for stored nothing, so there is nothing new to warn from. */
    @Test
    fun `a fetch that found no readings leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.NoReadings

        coVerify(exactly = 0) { scheduler.reconcile(any()) }
    }

    /**
     * Every landing, not only the first. Two fetches in a session — the hourly worker, then a
     * move — are two different series, and the second has as much claim on the queue as the
     * first.
     */
    @Test
    fun `each new series rebuilds them again`() = runTest {
        refreshState.value = RefreshState.Updated
        refreshState.value = RefreshState.InFlight
        refreshState.value = RefreshState.Updated

        coVerify(exactly = 2) { scheduler.reconcile(any()) }
    }
}
