package com.radami.migrainewatch.notifications

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PendingAlertNotification
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class LocalNotificationAlertQueueTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")
        val EVENT_START: Instant = Instant.parse("2026-08-25T00:00:00Z")
        val EVENT_END: Instant = Instant.parse("2026-08-25T20:00:00Z")
        val NOTIFY_AT: Instant = Instant.parse("2026-08-24T12:00:00Z")

        const val NAME = "alert_notification_drop_496560"
    }

    private class FakeCenter : LocalNotificationCenter {
        val pending = linkedMapOf<String, LocalNotification>()
        var permitted = true

        override suspend fun pendingIdentifiers(): Set<String> = pending.keys.toSet()

        override suspend fun schedule(notification: LocalNotification): Boolean {
            if (!permitted) return false
            pending[notification.identifier] = notification
            return true
        }

        override suspend fun cancel(identifier: String) {
            pending.remove(identifier)
        }
    }

    /** Mirrors the DAO's SQL; the real queries are checked against SQLite in iosTest. */
    private class FakeDao : NotifiedAlertDao {
        val rows = mutableListOf<NotifiedAlert>()

        override suspend fun insert(alert: NotifiedAlert) {
            rows.removeAll { it.startDateTime == alert.startDateTime && it.direction == alert.direction }
            rows += alert
        }

        override suspend fun getNotifiedSince(since: Instant) = rows.filter { it.notifiedDateTime >= since }

        override suspend fun deleteOlderThan(before: Instant) {
            rows.removeAll { it.notifiedDateTime < before }
        }

        override suspend fun deleteQueuedAhead(direction: String, from: Instant, until: Instant, now: Instant) {
            rows.removeAll {
                it.direction == direction && it.startDateTime >= from && it.startDateTime < until &&
                    it.notifiedDateTime > now
            }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }

    private class InMemoryDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            state.value = transform(state.value)
            return state.value
        }
    }

    private val center = FakeCenter()
    private val dao = FakeDao()
    private val userPreferences = UserPreferences(InMemoryDataStore())
    private val queue = LocalNotificationAlertQueue(center, dao, userPreferences, clock = object : Clock {
        override fun now(): Instant = NOW
    })

    private val alert = AlertWindow(start = EVENT_START, end = EVENT_END, delta = 9f, direction = PressureDirection.DROP)
    private val pending = PendingAlertNotification(alert, notifyAt = NOTIFY_AT, phase = AlertPhase.AHEAD)

    @Test
    fun aWarningIsScheduledWordedInTheLocationZone() = runTest {
        userPreferences.saveLocation(
            LocationData(source = "manual", lat = 27.7172, lon = 85.324, name = "Kathmandu", timezone = "Asia/Kathmandu")
        )

        queue.schedule(NAME, pending, NOW)

        assertEquals(
            LocalNotification(
                identifier = NAME,
                title = "Pressure drop (9.0 hPa in 24h)",
                body = "Starts Tuesday 05:45",
                fireAt = NOTIFY_AT
            ),
            center.pending.getValue(NAME)
        )
    }

    @Test
    fun aScheduledWarningIsRecordedForTheTimeItWillShow() = runTest {
        queue.schedule(NAME, pending, NOW)

        val threshold = userPreferences.settings.first().alertThresholdHpa
        assertEquals(
            listOf(NotifiedAlert(EVENT_START, EVENT_END, PressureDirection.DROP.wireName, threshold, NOTIFY_AT)),
            dao.rows
        )
    }

    @Test
    fun aWarningTheSystemRefusesIsNotRecorded() = runTest {
        center.permitted = false

        queue.schedule(NAME, pending, NOW)

        assertTrue(center.pending.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    /**
     * Scheduled while posting was allowed, then refused once it no longer is. The earlier copy
     * would fire unseen and its row would count the warning as sent, so it could never be shown.
     */
    @Test
    fun aWarningRefusedLaterIsWithdrawnWithItsRow() = runTest {
        queue.schedule(NAME, pending, NOW)
        center.permitted = false

        queue.schedule(NAME, pending, NOW)

        assertTrue(center.pending.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun aDriftedForecastReplacesTheRowItQueuedBefore() = runTest {
        queue.schedule(NAME, pending, NOW)
        val drifted = alert.copy(start = EVENT_START + 20.minutes)

        queue.schedule(NAME, pending.copy(alert = drifted), NOW)

        assertEquals(listOf(drifted.start), dao.rows.map { it.startDateTime })
        assertEquals(listOf(NAME), center.pending.keys.toList())
    }

    @Test
    fun cancellingRemovesTheNotificationAndItsQueuedRow() = runTest {
        queue.schedule(NAME, pending, NOW)

        queue.cancel(NAME)

        assertTrue(center.pending.isEmpty())
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun cancellingKeepsAWarningAlreadyShown() = runTest {
        val shown = NotifiedAlert(EVENT_START, EVENT_END, PressureDirection.DROP.wireName, 6f, NOW - 60.minutes)
        dao.insert(shown)

        queue.cancel(NAME)

        assertEquals(listOf(shown), dao.rows)
    }

    @Test
    fun onlyWarningNotificationsCountAsPending() = runTest {
        queue.schedule(NAME, pending, NOW)
        center.pending["something_else"] = center.pending.getValue(NAME).copy(identifier = "something_else")

        assertEquals(setOf(NAME), queue.pendingNames())
    }
}
