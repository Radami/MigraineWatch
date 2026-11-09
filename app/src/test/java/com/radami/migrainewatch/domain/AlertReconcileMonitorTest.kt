package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
 * The queued warnings must be rebuilt whenever what they are built from changes. The hourly
 * worker reconciles after its own fetch, but a location change also refetches from inside the
 * repository, a screen can start a fetch, and the alert settings change from the settings screen;
 * each of these needs a reconcile too, on both platforms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertReconcileMonitorTest {

    private val repository = mockk<PressureRepository>()
    private val userPreferences = mockk<UserPreferences>()
    private val reconciler = mockk<AlertReconciler>()

    private val refreshState = MutableStateFlow(RefreshState.InFlight)
    private val settings = MutableStateFlow(AppSettings())

    // Unconfined so a published change is acted on inline: the test can then say "a refresh has
    // landed" and read what happened without waiting on a clock.
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @Before
    fun setup() {
        every { repository.refreshState } returns refreshState
        every { userPreferences.settings } returns settings
        coEvery { reconciler.reconcile(any()) } returns
            ReconcileResult.Success(pending = 0, cancelled = 0)

        AlertReconcileMonitor(repository, userPreferences, reconciler, scope).start()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `nothing is rebuilt just for starting to watch`() = runTest {
        coVerify(exactly = 0) { reconciler.reconcile(any()) }
    }

    @Test
    fun `a series that lands rebuilds the pending warnings`() = runTest {
        refreshState.value = RefreshState.Updated

        coVerify(exactly = 1) { reconciler.reconcile(any()) }
    }

    /**
     * Nothing was stored, so the queue still matches what is there. Rebuilding it would recompute
     * the same set from the very series the failed fetch was trying to replace.
     */
    @Test
    fun `a failed fetch leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.Failed

        coVerify(exactly = 0) { reconciler.reconcile(any()) }
    }

    @Test
    fun `a fetch still out leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.InFlight

        coVerify(exactly = 0) { reconciler.reconcile(any()) }
    }

    /** A location the fetch found nothing for stored nothing, so there is nothing new to warn from. */
    @Test
    fun `a fetch that found no readings leaves the pending warnings alone`() = runTest {
        refreshState.value = RefreshState.NoReadings

        coVerify(exactly = 0) { reconciler.reconcile(any()) }
    }

    /** Every landing must reconcile, not just the first — a later fetch has equal claim on the queue. */
    @Test
    fun `each new series rebuilds them again`() = runTest {
        refreshState.value = RefreshState.Updated
        refreshState.value = RefreshState.InFlight
        refreshState.value = RefreshState.Updated

        coVerify(exactly = 2) { reconciler.reconcile(any()) }
    }

    /**
     * The refresh state is a state flow, which only keeps its latest value. A fetch landing while
     * a reconcile is still running must still get its own reconcile afterwards.
     */
    @Test
    fun `a series landing during a reconcile is rebuilt after it`() = runTest {
        val firstRunning = CompletableDeferred<Unit>()
        var runs = 0
        coEvery { reconciler.reconcile(any()) } coAnswers {
            runs++
            if (runs == 1) firstRunning.await()
            ReconcileResult.Success(pending = 0, cancelled = 0)
        }

        refreshState.value = RefreshState.Updated
        refreshState.value = RefreshState.InFlight
        refreshState.value = RefreshState.Updated
        firstRunning.complete(Unit)

        coVerify(exactly = 2) { reconciler.reconcile(any()) }
    }

    @Test
    fun `a new sensitivity rebuilds the pending warnings`() = runTest {
        settings.value = settings.value.copy(alertThresholdHpa = AlertSensitivity.HIGH.thresholdHpa)

        coVerify(exactly = 1) { reconciler.reconcile(any()) }
    }

    @Test
    fun `switching alerts off rebuilds the pending warnings`() = runTest {
        settings.value = settings.value.copy(notificationsEnabled = false)

        coVerify(exactly = 1) { reconciler.reconcile(any()) }
    }

    /**
     * Only the settings a warning depends on count. A moved location rebuilds them through the
     * refetch it triggers instead, once the new series has landed.
     */
    @Test
    fun `other settings changing leaves the pending warnings alone`() = runTest {
        settings.value = settings.value.copy(
            onboardingComplete = true,
            location = LocationData(source = "manual", lat = 47.37, lon = 8.54, name = "Zurich")
        )

        coVerify(exactly = 0) { reconciler.reconcile(any()) }
    }
}
