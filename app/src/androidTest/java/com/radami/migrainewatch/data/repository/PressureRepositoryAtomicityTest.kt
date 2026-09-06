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
 * Guards the one invariant the Today screen leans on: a refresh publishes a whole forecast or
 * none of it, never a series that has been stripped of its forecast and not yet refilled.
 *
 * The screen reads an all-[com.radami.migrainewatch.domain.OutlookRisk.Unknown] outlook as a
 * failed load and says so, so an observer that catches the gap between clearing the old
 * forecast and writing the new one renders "unable to load" over data that arrived intact —
 * the flicker this test exists to keep out.
 */
@RunWith(AndroidJUnit4::class)
class PressureRepositoryAtomicityTest {

    private companion object {
        /**
         * How many refreshes run under the collector. Room coalesces invalidations that land
         * close together, so a torn write is a race an observer can lose: a single refresh may
         * well publish nothing in between. Repetition is what turns "can tear" into "does".
         */
        const val REFRESH_ATTEMPTS = 30


        /** How far the fake forecast reaches, and how far back its history runs. */
        const val SERIES_HOURS = 48L

        /**
          * The point an emission is judged against. It sits inside the forecast half rather
          * than at its edge, so a series that still holds its forecast clears the bar however
          * far the clock has drifted since the readings were generated.
          */
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

        // The repository's own scope, which is where its fetches run. Cancelled with the
        // preferences scope in tearDown.
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
     * Fails when any emission shows a series mid-write.
     *
     * Two shapes count, because a write split into statements is visible at each of them: a
     * table momentarily emptied, and one holding history whose forecast has been cleared and
     * not yet replaced. The second is the one a live screen actually catches — it is wider
     * than the first and it is what the Today card reads as a failed load.
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
        // Seed before anything is watching, so every emission the collector sees is one a live
        // screen would have rendered rather than the empty state before the first fetch.
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

        // Nothing the fake serves can produce a partial series legitimately: every response
        // it returns reaches SERIES_HOURS ahead of the probe.
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
     * The same guarantee across a move, which takes the other write path: everything stored
     * describes the old city, so the replacement clears the table first. That delete is the
     * one this test exists for — run apart from its insert it would strip the series under a
     * live screen exactly as the forecast delete once did.
     *
     * One move is enough here, where [refreshNeverPublishesASeriesStrippedOfItsForecast] needs
     * many refreshes, because clearing the whole table opens a far wider window than clearing
     * only the forecast: measured against a deliberately split transaction, a single move was
     * caught in three runs out of three.
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
