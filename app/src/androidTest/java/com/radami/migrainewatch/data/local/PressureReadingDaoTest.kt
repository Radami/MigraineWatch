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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class PressureReadingDaoTest {

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
}
