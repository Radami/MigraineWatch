package com.radami.migrainewatch.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(AndroidSQLiteDriver())
            .build()
        dao = db.pressureReadingDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndGetReadings() = runBlocking {
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val readings = listOf(
            PressureReading(now, 1013f, 1000f, now),
            PressureReading(now + 1.hours, 1014f, 1001f, now)
        )
        
        dao.insertHistorical(readings)
        
        val result = dao.getReadingsInRange(now - 1.seconds, now + 3601.seconds).first()
        assertEquals(2, result.size)
        assertEquals(1013f, result[0].pressureMsl)
    }

    @Test
    fun deleteForecastOnlyDeletesFutureData() = runBlocking {
        val now = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val pastReading = PressureReading(now - 1.hours, 1010f, 1000f, now)
        val futureReading = PressureReading(now + 1.hours, 1020f, 1010f, now)
        
        dao.insertHistorical(listOf(pastReading))
        dao.insertForecast(listOf(futureReading))
        
        dao.deleteForecast(now)
        
        val allReadings = dao.getAllReadings().first()
        assertEquals(1, allReadings.size)
        assertEquals(1010f, allReadings[0].pressureMsl)
    }

    /**
     * A location move must clear old rows, not overwrite them. Readings are keyed by instant,
     * and Berlin/Kathmandu grids are 45 min apart, so REPLACE would collide with nothing and
     * both cities' readings would interleave into one bogus series.
     */
    @Test
    fun replaceAllReadingsClearsRowsTheNewSeriesCannotOverwrite() = runBlocking {
        val noonUtc = Instant.parse("2026-09-06T10:00:00Z")

        // Berlin: midday local lands on the hour in UTC.
        val berlin = (0 until SERIES_HOURS).map { hour ->
            PressureReading(noonUtc + hour.hours, 1013f, 1000f, noonUtc)
        }
        dao.insertHistorical(berlin)
        assertEquals(SERIES_HOURS, dao.getAllReadings().first().size)

        // Kathmandu: the same wall-clock hours, 45 minutes off Berlin's grid.
        val kathmandu = (0 until SERIES_HOURS).map { hour ->
            val at = noonUtc + hour.hours - 45.minutes
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
