package com.radami.migrainewatch.workers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.notifications.AlertNotifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The worker re-checks an event when its warning falls due, then posts and records it. Its
 * clock is the real one, so events are placed relative to the moment each test starts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertNotificationWorkerTest {

    private companion object {
        const val THRESHOLD_HPA = 6f
        val LOCATION_ZONE: ZoneId = ZoneId.of("Asia/Kathmandu")
        val NOTIFICATION_LOOKBACK: Duration = 7.days
    }

    private val alertUseCase = mockk<PressureAlertUseCase>()
    private val notifiedAlertDao = mockk<NotifiedAlertDao>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>()
    private val notifier = mockk<AlertNotifier>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Whole seconds: the scheduler hands the worker its event in epoch seconds. */
    private val startedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)

    private val ahead = AlertWindow(
        start = startedAt + 6.hours,
        end = startedAt + 26.hours,
        delta = 9f,
        direction = PressureDirection.DROP
    )

    private val underway = AlertWindow(
        start = startedAt - 2.hours,
        end = startedAt + 10.hours,
        delta = 9f,
        direction = PressureDirection.RISE
    )

    @Before
    fun setup() {
        val settings = AppSettings(
            alertThresholdHpa = THRESHOLD_HPA,
            location = LocationData(timezone = LOCATION_ZONE.id)
        )
        every { userPreferences.settings } returns flowOf(settings)
        coEvery { notifiedAlertDao.getNotifiedSince(any()) } returns emptyList()
        every { notifier.notify(any(), any(), any()) } returns true
    }

    /** Runs the worker for [alert], with [forecast] as what the use case currently detects. */
    private fun runWorker(
        alert: AlertWindow,
        forecast: List<AlertWindow> = listOf(alert)
    ): ListenableWorker.Result {
        coEvery { alertUseCase.currentAlerts(any()) } returns forecast

        val worker = TestListenableWorkerBuilder<AlertNotificationWorker>(context)
            .setInputData(AlertNotificationWorker.inputDataFor(alert))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = AlertNotificationWorker(
                    appContext,
                    workerParameters,
                    alertUseCase,
                    notifiedAlertDao,
                    userPreferences,
                    notifier
                )
            })
            .build()

        return runBlocking { worker.doWork() }
    }

    @Test
    fun `the posted warning is the event it was scheduled for`() {
        val posted = slot<AlertWindow>()
        every { notifier.notify(capture(posted), any(), any()) } returns true

        assertEquals(ListenableWorker.Result.success(), runWorker(ahead))
        assertEquals(ahead, posted.captured)
    }

    @Test
    fun `a sent warning is recorded with its event's window`() {
        val recorded = slot<NotifiedAlert>()
        coEvery { notifiedAlertDao.insert(capture(recorded)) } returns Unit

        runWorker(ahead)
        val finishedAt = Clock.System.now()

        assertEquals(ahead.start, recorded.captured.startDateTime)
        assertEquals(ahead.end, recorded.captured.endDateTime)
        assertEquals(PressureDirection.DROP.wireName, recorded.captured.direction)
        assertEquals(THRESHOLD_HPA, recorded.captured.thresholdHpa, 0f)

        val notifiedAt = recorded.captured.notifiedDateTime
        assertTrue(notifiedAt >= startedAt && notifiedAt <= finishedAt)
    }

    @Test
    fun `delivered warnings are read back over the last seven days`() {
        val since = slot<Instant>()
        coEvery { notifiedAlertDao.getNotifiedSince(capture(since)) } returns emptyList()

        runWorker(ahead)
        val finishedAt = Clock.System.now()

        val lookedBack = since.captured
        assertTrue(lookedBack >= startedAt - NOTIFICATION_LOOKBACK)
        assertTrue(lookedBack <= finishedAt - NOTIFICATION_LOOKBACK)
    }

    @Test
    fun `an event already warned about is not posted again`() {
        val delivered = NotifiedAlert(
            startDateTime = ahead.start,
            endDateTime = ahead.end,
            direction = PressureDirection.DROP.wireName,
            thresholdHpa = THRESHOLD_HPA,
            notifiedDateTime = startedAt - 1.hours
        )
        coEvery { notifiedAlertDao.getNotifiedSince(any()) } returns listOf(delivered)

        assertEquals(ListenableWorker.Result.success(), runWorker(ahead))
        verify(exactly = 0) { notifier.notify(any(), any(), any()) }
        coVerify(exactly = 0) { notifiedAlertDao.insert(any()) }
    }

    @Test
    fun `an event no longer forecast is not posted`() {
        assertEquals(ListenableWorker.Result.success(), runWorker(ahead, forecast = emptyList()))
        verify(exactly = 0) { notifier.notify(any(), any(), any()) }
    }

    @Test
    fun `an event still ahead is posted as ahead`() {
        runWorker(ahead)

        verify(exactly = 1) { notifier.notify(any(), AlertPhase.AHEAD, any()) }
    }

    @Test
    fun `an event already under way is posted as under way`() {
        runWorker(underway)

        verify(exactly = 1) { notifier.notify(any(), AlertPhase.UNDERWAY, any()) }
    }

    @Test
    fun `times are worded in the location's zone`() {
        runWorker(ahead)

        verify(exactly = 1) { notifier.notify(any(), any(), LOCATION_ZONE) }
    }

    @Test
    fun `a warning the system refused is retried, not recorded`() {
        every { notifier.notify(any(), any(), any()) } returns false

        assertEquals(ListenableWorker.Result.retry(), runWorker(ahead))
        coVerify(exactly = 0) { notifiedAlertDao.insert(any()) }
    }
}
