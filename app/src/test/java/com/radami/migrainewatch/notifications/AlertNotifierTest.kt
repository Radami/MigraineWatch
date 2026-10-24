package com.radami.migrainewatch.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * Pins the tray id a warning is posted under. A re-posted warning replaces the old one by id,
 * so an id computed differently after an update would leave the old warning stranded beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlertNotifierTest {

    private companion object {
        /** The id a drop starting at 2026-08-25T00:00Z has always been posted under. */
        const val EVENT_NOTIFICATION_ID = 926693807

        /** +05:45, so a zone mix-up can't hide behind a whole-hour offset. */
        val KATHMANDU: ZoneId = ZoneId.of("Asia/Kathmandu")
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val permissionMonitor = mockk<NotificationPermissionMonitor>()
    private lateinit var notifier: AlertNotifier

    private val alert = AlertWindow(
        start = Instant.parse("2026-08-25T00:00:00Z"),
        end = Instant.parse("2026-08-25T20:00:00Z"),
        delta = 9f,
        direction = PressureDirection.DROP
    )

    @Before
    fun setup() {
        every { permissionMonitor.canPost() } returns true
        notifier = AlertNotifier(context, permissionMonitor)
    }

    @Test
    fun `a warning is posted under the id its event has always had`() {
        assertEquals(true, notifier.notify(alert, AlertPhase.AHEAD, KATHMANDU))

        assertNotNull(shadowOf(notificationManager).getNotification(EVENT_NOTIFICATION_ID))
    }

    @Test
    fun `posting the same event again replaces it rather than stacking`() {
        notifier.notify(alert, AlertPhase.AHEAD, KATHMANDU)
        notifier.notify(alert.copy(end = alert.end.plusSeconds(3600)), AlertPhase.UNDERWAY, KATHMANDU)

        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `the warning words its times in the zone it is given`() {
        val underway = alert.copy(
            start = Instant.parse("2026-08-24T06:15:00Z"),
            end = Instant.parse("2026-08-24T21:45:00Z")
        )

        notifier.notify(underway, AlertPhase.UNDERWAY, KATHMANDU)

        val posted = shadowOf(notificationManager).allNotifications.single()
        assertEquals(
            "Underway since Monday 12:00 · eases Tuesday 03:30",
            posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
    }
}
