package com.radami.migrainewatch.domain

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/**
 * Pins what the scheduler hands WorkManager. The work name in particular must never drift:
 * warnings already queued on a device are replaced by name, so a name computed differently
 * after an update would queue a second warning beside the first instead of replacing it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertNotificationSchedulerTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")

        /** 36 hours ahead of [NOW], so its warning is due 24 hours from now. */
        val EVENT_START: Instant = Instant.parse("2026-08-25T00:00:00Z")
        val EVENT_END: Instant = Instant.parse("2026-08-25T20:00:00Z")

        /** The name a warning for this drop has carried since names were hour-based. */
        const val EVENT_WORK_NAME = "alert_notification_drop_496560"

        /** Tag every scheduled warning carries, so the pending set can be read back. */
        const val WORK_TAG = "alert_notification"
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

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlertNotificationScheduler

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)

        every { userPreferences.settings } returns flowOf(AppSettings())
        coEvery { alertUseCase.currentAlerts(any()) } returns listOf(alert)
        coEvery { notifiedAlertDao.getNotifiedSince(any()) } returns emptyList()

        scheduler = AlertNotificationScheduler(workManager, alertUseCase, notifiedAlertDao, userPreferences)
    }

    private fun reconcile(): ReconcileResult = runBlocking { scheduler.reconcile(NOW) }

    private fun queued(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get()

    @Test
    fun `a warning is queued under the name its event has always had`() {
        assertEquals(ReconcileResult.Success(pending = 1, cancelled = 0), reconcile())

        val info = queued(EVENT_WORK_NAME).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(WORK_TAG in info.tags)
    }

    @Test
    fun `the warning waits until the lead time before the event`() {
        reconcile()

        assertEquals(Duration.ofHours(24).toMillis(), queued(EVENT_WORK_NAME).single().initialDelayMillis)
    }

    @Test
    fun `delivered warnings are read back over the last seven days`() {
        val since = slot<Instant>()
        coEvery { notifiedAlertDao.getNotifiedSince(capture(since)) } returns emptyList()

        reconcile()

        assertEquals(Instant.parse("2026-08-16T12:00:00Z"), since.captured)
    }

    @Test
    fun `delivered warnings older than thirty days are pruned`() {
        reconcile()

        coVerify(exactly = 1) { notifiedAlertDao.deleteOlderThan(Instant.parse("2026-07-24T12:00:00Z")) }
    }

    @Test
    fun `an event already warned about is not queued again`() {
        val delivered = NotifiedAlert(
            startDateTime = EVENT_START,
            endDateTime = EVENT_END,
            direction = PressureDirection.DROP.wireName,
            thresholdHpa = 6f,
            notifiedDateTime = Instant.parse("2026-08-22T12:00:00Z")
        )
        coEvery { notifiedAlertDao.getNotifiedSince(any()) } returns listOf(delivered)

        assertEquals(ReconcileResult.Success(pending = 0, cancelled = 0), reconcile())
        assertTrue(queued(EVENT_WORK_NAME).isEmpty())
    }

    @Test
    fun `a warning whose event left the forecast is cancelled`() {
        reconcile()
        coEvery { alertUseCase.currentAlerts(any()) } returns emptyList()

        assertEquals(ReconcileResult.Success(pending = 0, cancelled = 1), reconcile())
        assertEquals(WorkInfo.State.CANCELLED, queued(EVENT_WORK_NAME).single().state)
    }
}
