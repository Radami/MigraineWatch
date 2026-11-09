package com.radami.migrainewatch

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.buildAppDatabase
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Runs the history queries against real SQLite, which a fake DAO can't vouch for. */
class NotifiedAlertDaoTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")
        val HOUR_START: Instant = Instant.parse("2026-08-25T00:00:00Z")
    }

    private val database: AppDatabase =
        buildAppDatabase(Room.inMemoryDatabaseBuilder<AppDatabase>(), BundledSQLiteDriver())
    private val dao = database.notifiedAlertDao()

    @AfterTest
    fun closeDatabase() {
        database.close()
    }

    private fun row(start: Instant, direction: PressureDirection, notifiedAt: Instant) = NotifiedAlert(
        startDateTime = start,
        endDateTime = start + 10.hours,
        direction = direction.wireName,
        thresholdHpa = 6f,
        notifiedDateTime = notifiedAt
    )

    @Test
    fun onlyQueuedRowsForThatEventAndHourAreForgotten() = runBlocking {
        val queued = row(HOUR_START + 20.minutes, PressureDirection.DROP, notifiedAt = NOW + 12.hours)
        val alreadyDue = row(HOUR_START, PressureDirection.DROP, notifiedAt = NOW - 1.hours)
        val otherDirection = row(HOUR_START, PressureDirection.RISE, notifiedAt = NOW + 12.hours)
        val nextHour = row(HOUR_START + 1.hours, PressureDirection.DROP, notifiedAt = NOW + 12.hours)
        listOf(queued, alreadyDue, otherDirection, nextHour).forEach { dao.insert(it) }

        dao.deleteQueuedAhead(
            direction = PressureDirection.DROP.wireName,
            from = HOUR_START,
            until = HOUR_START + 1.hours,
            now = NOW
        )

        assertEquals(
            setOf(alreadyDue, otherDirection, nextHour),
            dao.getNotifiedSince(Instant.fromEpochSeconds(0)).toSet()
        )
    }

    @Test
    fun aRowDueExactlyNowCountsAsHistory() = runBlocking {
        val dueNow = row(HOUR_START, PressureDirection.DROP, notifiedAt = NOW)
        dao.insert(dueNow)

        dao.deleteQueuedAhead(PressureDirection.DROP.wireName, HOUR_START, HOUR_START + 1.hours, NOW)

        assertEquals(listOf(dueNow), dao.getNotifiedSince(Instant.fromEpochSeconds(0)))
    }
}
