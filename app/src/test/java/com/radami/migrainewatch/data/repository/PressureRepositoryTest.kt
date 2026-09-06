package com.radami.migrainewatch.data.repository

import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.dto.HourlyData
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class PressureRepositoryTest {

    private companion object {
        const val BERLIN_LAT = 52.52
        const val BERLIN_LON = 13.41

        /**
         * Somewhere on a quarter-hour offset, which is the case a plain refresh cannot clean
         * up: readings are keyed by instant and both APIs report hourly on the hour in local
         * time, so Kathmandu's grid sits 45 minutes off Berlin's and nothing collides.
         */
        val KATHMANDU = LocationData(
            source = "manual",
            lat = 27.71,
            lon = 85.32,
            name = "Kathmandu, Nepal",
            timezone = "Asia/Kathmandu"
        )

        /** Enough callers that a fetch per caller would be unmistakable in the count. */
        const val OVERLAPPING_CALLERS = 5
    }

    private val dao = mockk<PressureReadingDao>(relaxed = true)
    private val forecastApi = mockk<OpenMeteoApi>()
    private val archiveApi = mockk<OpenMeteoArchiveApi>()
    private val prefs = mockk<UserPreferences>()

    // The repository fetches here rather than in the caller's scope. Unconfined so a started
    // fetch runs inline as far as its first suspension, which is what lets a test say "the
    // fetch is now in flight" without waiting on a clock.
    private val refreshScope = CoroutineScope(UnconfinedTestDispatcher())

    /**
     * Mutable so a test can move the user. Stubbed before the repository is built, because it
     * starts watching the location the moment it is constructed.
     */
    private val settings = MutableStateFlow(
        AppSettings(location = LocationData(lat = BERLIN_LAT, lon = BERLIN_LON, name = "Berlin"))
    )

    private lateinit var repository: PressureRepository

    @Before
    fun setup() {
        every { prefs.settings } returns settings
        repository = PressureRepository(dao, forecastApi, archiveApi, prefs, refreshScope)
    }

    @After
    fun tearDown() {
        refreshScope.cancel()
    }

    /** One hour of readings, which is all any of these tests needs the API to return. */
    private fun response() = OpenMeteoResponse(
        latitude = BERLIN_LAT,
        longitude = BERLIN_LON,
        timezone = "UTC",
        hourly = HourlyData(
            time = listOf("2023-10-01T12:00"),
            pressureMsl = listOf(1013.0f),
            surfacePressure = listOf(1000.0f)
        )
    )

    /**
     * Skips the archive gap fill, so a test about the forecast fetch is only about that: the
     * repository reaches for the archive when its newest stored history is over 30 days old.
     */
    private fun stubRecentHistory() {
        val now = Instant.now()
        coEvery { dao.getLatestHistorical(any()) } returns PressureReading(now, 1013f, 1000f, now)
    }

    @Test
    fun `refresh fetches the forecast and saves it`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        repository.refresh()

        coVerify { forecastApi.getForecast(BERLIN_LAT, BERLIN_LON, timezone = "UTC") }
        coVerify { dao.replaceSeries(any(), any(), any()) }
    }

    /**
     * The forecast has to be replaced in one transaction, not cleared and refilled in two.
     * The screens observe the readings table, and an observer that reads between a bare
     * delete and its insert sees a series with no forecast in it — which the Today screen
     * reports as a failed load, flickering an error over data that arrived intact.
     *
     * Asserted on the calls rather than on what an observer saw: the tear is a race, so a
     * behavioural test of it can pass on a broken repository. This one cannot.
     */
    @Test
    fun `refresh replaces the forecast atomically rather than deleting and reinserting`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        repository.refresh()

        coVerify(exactly = 1) { dao.replaceSeries(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteForecast(any()) }
        coVerify(exactly = 0) { dao.insertForecast(any()) }
    }

    /**
     * Callers overlap in the running app — the Today screen refreshes as it opens, the
     * Pressure screen when its data is stale, the worker on its own schedule — and separate
     * fetches racing each other write in whatever order they finish, so the series that lands
     * last need not be the one fetched last. A caller arriving while a fetch is open has to
     * join it rather than start another.
     */
    @Test
    fun `overlapping refreshes share one fetch`() = runTest {
        val fetches = AtomicInteger()

        // Held open so every caller is inside the repository at once. Without it the first
        // fetch could finish before the second caller arrived, and the test would pass on a
        // repository that had never shared anything.
        val releaseFetch = CompletableDeferred<Unit>()
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } coAnswers {
            fetches.incrementAndGet()
            releaseFetch.await()
            response()
        }
        stubRecentHistory()

        // The test scope's own async, not backgroundScope: advanceUntilIdle drives foreground
        // work, and background work only runs when the test would otherwise be waiting — so
        // background callers would still be queued here, each going on to open its own fetch.
        val callers = List(OVERLAPPING_CALLERS) { async { repository.refresh() } }
        advanceUntilIdle()

        releaseFetch.complete(Unit)
        callers.awaitAll()

        assertEquals(1, fetches.get())
        coVerify(exactly = 1) { dao.replaceSeries(any(), any(), any()) }
    }

    /** A finished refresh is not one to join: the next caller starts a fetch of its own. */
    @Test
    fun `a refresh after the previous one finished fetches again`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        repository.refresh()
        repository.refresh()

        coVerify(exactly = 2) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }

    /**
     * Everything stored describes where the user was, so a move refetches. Nothing asks the
     * repository to do this — the screen that saves a location just saves it — which is the
     * point: the series belongs to a place, so noticing the place changed belongs here.
     */
    @Test
    fun `moving refetches for the new location`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        settings.value = AppSettings(location = KATHMANDU)
        advanceUntilIdle()

        coVerify {
            forecastApi.getForecast(KATHMANDU.lat, KATHMANDU.lon, timezone = KATHMANDU.timezone)
        }
    }

    /**
     * And discards what was stored rather than laying the new city over it. The old rows are
     * only overwritten where the two grids agree, which between Berlin and Kathmandu is
     * nowhere — left in place they would interleave into one series describing neither city,
     * and the detector would read the seam between them as pressure moving.
     *
     * Asserted on the calls, like the atomicity test above and for the same reason: a delete
     * and an insert that a screen can read between is the defect, so the test has to be about
     * how the write is made, not about what a reader happened to catch.
     */
    @Test
    fun `moving replaces the stored series rather than merging into it`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        settings.value = AppSettings(location = KATHMANDU)
        advanceUntilIdle()

        coVerify(exactly = 1) { dao.replaceAllReadings(any(), any()) }
        coVerify(exactly = 0) { dao.deleteAllReadings() }
        coVerify(exactly = 0) { dao.replaceSeries(any(), any(), any()) }
    }

    /** Where the data already is. Refetching for it on every start would be a fetch for nothing. */
    @Test
    fun `the location in force at startup is not treated as a move`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        advanceUntilIdle()

        coVerify(exactly = 0) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }

    /**
     * The name is a label for the user, not part of what was fetched. Re-picking one city under
     * another spelling — or by GPS after having typed it — describes the same readings.
     */
    @Test
    fun `renaming a location without moving it does not refetch`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        settings.value = AppSettings(
            location = LocationData(
                source = "gps",
                lat = BERLIN_LAT,
                lon = BERLIN_LON,
                name = "Berlin, State of Berlin, Germany"
            )
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }

    @Test
    fun `refresh skips when no location is set`() = runTest {
        settings.value = AppSettings(location = LocationData(lat = 0.0, lon = 0.0))

        repository.refresh()

        coVerify(exactly = 0) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }
}
