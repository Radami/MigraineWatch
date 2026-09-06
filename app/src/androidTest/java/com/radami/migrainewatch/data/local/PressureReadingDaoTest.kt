package com.radami.migrainewatch.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.model.PressureReading
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class PressureReadingDaoTest {

    private companion object {
        /** A short run of hourly readings — enough to have a grid, small enough to read. */
        const val SERIES_HOURS = 6
    }

    private lateinit var db: AppDatabase
    private lateinit var dao: PressureReadingDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.pressureReadingDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetReadings() = runBlocking {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val readings = listOf(
            PressureReading(now, 1013f, 1000f, now),
            PressureReading(now.plus(1, ChronoUnit.HOURS), 1014f, 1001f, now)
        )
        
        dao.insertHistorical(readings)
        
        val result = dao.getReadingsInRange(now.minusSeconds(1), now.plusSeconds(3601)).first()
        assertEquals(2, result.size)
        assertEquals(1013f, result[0].pressureMsl)
    }

    @Test
    fun deleteForecastOnlyDeletesFutureData() = runBlocking {
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val pastReading = PressureReading(now.minus(1, ChronoUnit.HOURS), 1010f, 1000f, now)
        val futureReading = PressureReading(now.plus(1, ChronoUnit.HOURS), 1020f, 1010f, now)
        
        dao.insertHistorical(listOf(pastReading))
        dao.insertForecast(listOf(futureReading))
        
        dao.deleteForecast(now)
        
        val allReadings = dao.getAllReadings().first()
        assertEquals(1, allReadings.size)
        assertEquals(1010f, allReadings[0].pressureMsl)
    }

    /**
     * A move has to clear what is stored, not write over it.
     *
     * Readings are keyed by instant, and both Open-Meteo endpoints report hourly on the hour in
     * *local* time, so two cities share a grid only when their offsets differ by whole hours.
     * Berlin sits on :00 and Kathmandu on :15 — REPLACE collides with nothing, and the two
     * cities' readings would interleave into one series describing neither.
     */
    @Test
    fun replaceAllReadingsClearsRowsTheNewSeriesCannotOverwrite() = runBlocking {
        val noonUtc = Instant.parse("2026-09-06T10:00:00Z")

        // Berlin: midday local lands on the hour in UTC.
        val berlin = (0 until SERIES_HOURS).map { hour ->
            PressureReading(noonUtc.plus(hour.toLong(), ChronoUnit.HOURS), 1013f, 1000f, noonUtc)
        }
        dao.insertHistorical(berlin)
        assertEquals(SERIES_HOURS, dao.getAllReadings().first().size)

        // Kathmandu: the same wall-clock hours, 45 minutes off Berlin's grid.
        val kathmandu = (0 until SERIES_HOURS).map { hour ->
            val at = noonUtc.plus(hour.toLong(), ChronoUnit.HOURS).minus(45, ChronoUnit.MINUTES)
            PressureReading(at, 1005f, 995f, noonUtc)
        }

        dao.replaceAllReadings(historical = kathmandu, forecast = emptyList())

        val stored = dao.getAllReadings().first()
        assertEquals(SERIES_HOURS, stored.size)
        assertTrue(
            "Berlin's readings survived a move to Kathmandu",
            stored.none { it.dateTime in berlin.map { reading -> reading.dateTime } }
        )
    }
}
