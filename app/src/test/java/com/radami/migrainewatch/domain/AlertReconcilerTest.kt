package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Pins the reconcile algorithm against a fake queue, apart from any platform: what it asks a
 * queue to hold and drop, and what it reports back.
 */
class AlertReconcilerTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")

        val EVENT_START: Instant = Instant.parse("2026-08-25T00:00:00Z")
        val EVENT_END: Instant = Instant.parse("2026-08-25T20:00:00Z")

        const val EVENT_NAME = "alert_notification_drop_496560"
    }

    private class FakeQueue : AlertWarningQueue {
        val pending = linkedMapOf<String, PendingAlertNotification>()
        val cancelled = mutableListOf<String>()

        override suspend fun pendingNames(): Set<String> = pending.keys.toSet()

        override suspend fun cancel(name: String) {
            cancelled += name
            pending.remove(name)
        }

        override suspend fun schedule(name: String, notification: PendingAlertNotification, now: Instant) {
            pending[name] = notification
        }
    }

    private val alert = AlertWindow(
        start = EVENT_START,
        end = EVENT_END,
        delta = 9f,
        direction = PressureDirection.DROP
    )

    private val alertUseCase = mockk<PressureAlertUseCase>()
    private val notifiedAlertDao = mockk<NotifiedAlertDao>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>()
    private val queue = FakeQueue()

    private lateinit var reconciler: AlertReconciler

    @Before
    fun setup() {
        every { userPreferences.settings } returns flowOf(AppSettings())
        coEvery { alertUseCase.currentAlerts(any()) } returns listOf(alert)
        coEvery { notifiedAlertDao.getNotifiedSince(any()) } returns emptyList()

        reconciler = AlertReconciler(queue, alertUseCase, notifiedAlertDao, userPreferences)
    }

    private fun reconcile(): ReconcileResult = runBlocking { reconciler.reconcile(NOW) }

    @Test
    fun `a qualifying event is queued under its event name`() {
        assertEquals(ReconcileResult.Success(pending = 1, cancelled = 0), reconcile())

        assertEquals(listOf(EVENT_NAME), queue.pending.keys.toList())
        assertEquals(alert, queue.pending.getValue(EVENT_NAME).alert)
        assertEquals(Instant.parse("2026-08-24T12:00:00Z"), queue.pending.getValue(EVENT_NAME).notifyAt)
    }

    @Test
    fun `the name is the same for a forecast that drifts within the hour`() {
        reconcile()
        val drifted = alert.copy(start = EVENT_START + 20.minutes)
        coEvery { alertUseCase.currentAlerts(any()) } returns listOf(drifted)

        assertEquals(ReconcileResult.Success(pending = 1, cancelled = 0), reconcile())

        assertEquals(listOf(EVENT_NAME), queue.pending.keys.toList())
        assertEquals(drifted, queue.pending.getValue(EVENT_NAME).alert)
        assertTrue(queue.cancelled.isEmpty())
    }

    @Test
    fun `a queued warning whose event left the forecast is cancelled`() {
        reconcile()
        coEvery { alertUseCase.currentAlerts(any()) } returns emptyList()

        assertEquals(ReconcileResult.Success(pending = 0, cancelled = 1), reconcile())

        assertEquals(listOf(EVENT_NAME), queue.cancelled)
        assertTrue(queue.pending.isEmpty())
    }

    @Test
    fun `a warning that still qualifies is not cancelled`() {
        reconcile()
        reconcile()

        assertTrue(queue.cancelled.isEmpty())
        assertEquals(listOf(EVENT_NAME), queue.pending.keys.toList())
    }

    @Test
    fun `nothing is queued while notifications are switched off`() {
        every { userPreferences.settings } returns flowOf(AppSettings(notificationsEnabled = false))

        assertEquals(ReconcileResult.Success(pending = 0, cancelled = 0), reconcile())

        assertTrue(queue.pending.isEmpty())
    }

    @Test
    fun `switching notifications off cancels what was queued`() {
        reconcile()
        every { userPreferences.settings } returns flowOf(AppSettings(notificationsEnabled = false))

        assertEquals(ReconcileResult.Success(pending = 0, cancelled = 1), reconcile())

        assertTrue(queue.pending.isEmpty())
    }

    @Test
    fun `a failure is reported as a value and leaves the queue alone`() {
        reconcile()
        val boom = IllegalStateException("db unavailable")
        coEvery { alertUseCase.currentAlerts(any()) } throws boom

        assertEquals(ReconcileResult.Failed(boom), reconcile())

        assertEquals(listOf(EVENT_NAME), queue.pending.keys.toList())
        assertTrue(queue.cancelled.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `every reconcile is published on results`() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<ReconcileResult>()
        val collector = launch { reconciler.results.collect { seen += it } }

        reconciler.reconcile(NOW)
        coEvery { alertUseCase.currentAlerts(any()) } returns emptyList()
        reconciler.reconcile(NOW)
        collector.cancel()

        assertEquals(
            listOf(
                ReconcileResult.Success(pending = 1, cancelled = 0),
                ReconcileResult.Success(pending = 0, cancelled = 1)
            ),
            seen
        )
    }

    @Test
    fun `old delivered warnings are pruned after a successful reconcile`() {
        reconcile()

        coVerify(exactly = 1) { notifiedAlertDao.deleteOlderThan(Instant.parse("2026-07-24T12:00:00Z")) }
    }
}
