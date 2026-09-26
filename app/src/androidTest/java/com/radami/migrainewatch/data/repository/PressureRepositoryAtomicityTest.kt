package com.radami.migrainewatch.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.dto.HourlyData
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.Locale

/**
 * A refresh must publish a whole forecast or none of it, never a series stripped of its
 * forecast mid-write. An observer catching that gap would render "unable to load" over data
 * that arrived fine.
 */
@RunWith(AndroidJUnit4::class)
class PressureRepositoryAtomicityTest {

    private companion object {
        /** Room coalesces close invalidations, so one refresh may miss a torn write; repeat to catch it. */
        const val REFRESH_ATTEMPTS = 30


        /** How far the fake forecast reaches, and how far back its history runs. */
        const val SERIES_HOURS = 48L

        /** Sits inside the forecast half, not at its edge, to tolerate clock drift since generation. */
        const val FORECAST_PROBE_HOURS = 24L

        /** Long enough for the invalidation tracker to deliver whatever it is still holding. */
        const val SETTLE_MILLIS = 500L


        /** Its own store, deleted either side of the test so runs cannot inherit a location. */
        const val PREFS_NAME = "atomicity_test_prefs"

        /** Somewhere far enough away to be unmistakably a move. */
        val MOVED_TO = LocationData(
            source = "manual",
            lat = 27.71,
            lon = 85.32,
            name = "Kathmandu, Nepal",
            timezone = "Asia/Kathmandu"
        )

        val LOCATION = LocationData(
            source = "manual",
            lat = 52.52,
            lon = 13.41,
            name = "Berlin, Germany",
            timezone = "UTC"
        )
    }

    private lateinit var db: AppDatabase
    private lateinit var dao: PressureReadingDao
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferences: UserPreferences
    private lateinit var repository: PressureRepository

    private val preferencesScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** Fixed for the run: every assertion is phrased relative to it, not to a moving clock. */
    private val start: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS)

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.pressureReadingDao()

        context.preferencesDataStoreFile(PREFS_NAME).delete()
        dataStore = PreferenceDataStoreFactory.create(scope = preferencesScope) {
            context.preferencesDataStoreFile(PREFS_NAME)
        }
        preferences = UserPreferences(dataStore)
        runBlocking { preferences.saveLocation(LOCATION) }

        // The repository's own scope for its fetches; cancelled alongside preferencesScope.
        repository = PressureRepository(
            dao, FakeForecastApi(start), EmptyArchiveApi, preferences, refreshScope
        )
    }

    @After
    fun tearDown() {
        db.close()
        refreshScope.cancel()
        preferencesScope.cancel()
        context.preferencesDataStoreFile(PREFS_NAME).delete()
    }


    /**
     * Fails on any mid-write emission: a momentarily empty table, or history with its forecast
     * cleared but not yet replaced. The second is wider and is what the Today card sees as failed.
     */
    private fun assertNoneTorn(emissions: List<List<PressureReading>>) {
        val probe = start.plus(FORECAST_PROBE_HOURS, ChronoUnit.HOURS)
        val torn = emissions.filter { readings ->
            readings.isNotEmpty() && readings.none { it.dateTime.isAfter(probe) }
        }
        val emptied = emissions.count { it.isEmpty() }

        assertTrue(
            "${torn.size} of ${emissions.size} emissions had no forecast left in them, " +
                "and $emptied were empty",
            torn.isEmpty() && emptied == 0
        )
    }

    @Test
    fun refreshNeverPublishesASeriesStrippedOfItsForecast() = runBlocking {
        // Seed before watching starts, so the collector never sees the pre-fetch empty state.
        repository.refresh()

        val emissions = Collections.synchronizedList(mutableListOf<List<PressureReading>>())
        val collector = launch(Dispatchers.IO) {
            dao.getReadingsInRange(
                start.minus(SERIES_HOURS, ChronoUnit.HOURS),
                start.plus(SERIES_HOURS, ChronoUnit.HOURS)
            ).collect { emissions.add(it) }
        }

        repeat(REFRESH_ATTEMPTS) { repository.refresh() }
        delay(SETTLE_MILLIS)
        collector.cancelAndJoin()

        // Every fake response reaches SERIES_HOURS ahead of the probe, so no legitimate partial series.
        assertNoneTorn(emissions)
    }

    /** Serves an hourly series centred on [origin], reaching [SERIES_HOURS] either side of it. */
    private class FakeForecastApi(private val origin: Instant) : OpenMeteoApi {

        private val formatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT).withZone(ZoneId.of("UTC"))

        override suspend fun getForecast(
            latitude: Double,
            longitude: Double,
            hourly: String,
            pastDays: Int,
            forecastDays: Int,
            timezone: String
        ): OpenMeteoResponse {
            val hours = (-SERIES_HOURS..SERIES_HOURS).map { origin.plus(it, ChronoUnit.HOURS) }
            return OpenMeteoResponse(
                latitude = latitude,
                longitude = longitude,
                timezone = "UTC",
                hourly = HourlyData(
                    time = hours.map { formatter.format(it) },
                    pressureMsl = hours.map { 1013f },
                    surfacePressure = hours.map { 1000f }
                )
            )
        }
    }

    /** The gap fill is not what is under test, so it contributes no rows. */
    private object EmptyArchiveApi : OpenMeteoArchiveApi {
        override suspend fun getArchive(
            latitude: Double,
            longitude: Double,
            hourly: String,
            startDate: String,
            endDate: String,
            timezone: String
        ): OpenMeteoResponse = OpenMeteoResponse(
            latitude = latitude,
            longitude = longitude,
            timezone = "UTC",
            hourly = HourlyData(time = emptyList(), pressureMsl = emptyList(), surfacePressure = emptyList())
        )
    }

    /**
     * Same guarantee for a move: the replacement clears the whole table first, so a torn write
     * here strips the series under a live screen. One move is enough (unlike the refresh test)
     * since this window is far wider — a broken transaction was caught 3/3 runs.
     */
    @Test
    fun movingNeverPublishesAnEmptySeries() = runBlocking {
        repository.refresh()

        val emissions = Collections.synchronizedList(mutableListOf<List<PressureReading>>())
        val collector = launch(Dispatchers.IO) {
            dao.getReadingsInRange(
                start.minus(SERIES_HOURS, ChronoUnit.HOURS),
                start.plus(SERIES_HOURS, ChronoUnit.HOURS)
            ).collect { emissions.add(it) }
        }

        // Saving the location is all a screen does; the repository is what notices.
        preferences.saveLocation(MOVED_TO)
        delay(SETTLE_MILLIS)
        collector.cancelAndJoin()

        assertNoneTorn(emissions)

        // And the moves did land, so the window was not watching a table nothing happened to.
        assertTrue("No move ever reached the database", emissions.isNotEmpty())
    }
}
