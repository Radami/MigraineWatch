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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.Collections
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

        /** Somewhere else again, so a second move is unmistakably a second move. */
        val REYKJAVIK = LocationData(
            source = "manual",
            lat = 64.15,
            lon = -21.94,
            name = "Reykjavik, Iceland",
            timezone = "Atlantic/Reykjavik"
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

        // Reported as its own outcome, not as a failure: there is nothing wrong with the app
        // or the network, and the card that shows this must not blame either.
        assertEquals(RefreshState.NoLocation, repository.refresh())
        coVerify(exactly = 0) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }

    /**
     * A fetch that cannot reach the network has to come back as a value.
     *
     * Thrown instead, it would reach three places that cannot take it: the worker, which would
     * report the run a success and wait a full interval for its next chance; a ViewModel's init,
     * which has no handler and would take the process down; and the location collector, which
     * is one coroutine for the life of the process — see the move test below.
     */
    @Test
    fun `a failed fetch is reported rather than thrown`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } throws IOException("offline")
        stubRecentHistory()

        assertEquals(RefreshState.Failed, repository.refresh())
        assertEquals(RefreshState.Failed, repository.refreshState.value)
    }

    /** And leaves the stored series alone, so a stale forecast survives a failed refresh. */
    @Test
    fun `a failed fetch writes nothing`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } throws IOException("offline")
        stubRecentHistory()

        repository.refresh()

        coVerify(exactly = 0) { dao.replaceSeries(any(), any(), any()) }
        coVerify(exactly = 0) { dao.replaceAllReadings(any(), any()) }
    }

    /**
     * The storage a fetch consults on its way to the network used to sit outside every catch,
     * so a Room failure there threw out of the fetch entirely — into a ViewModel's init, which
     * has no handler, or into the location collector, which is one coroutine for the life of
     * the process and is never replaced.
     *
     * The refresh still succeeds: what this call is for is the archive gap fill, and the
     * forecast endpoint returns its 30 days of history without it.
     */
    @Test
    fun `a storage failure on the way to the network is not thrown out of refresh`() = runTest {
        coEvery { dao.getLatestHistorical(any()) } throws IllegalStateException("database closed")
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()

        assertEquals(RefreshState.Updated, repository.refresh())
        coVerify(exactly = 1) { dao.replaceSeries(any(), any(), any()) }
    }

    /**
     * And the collector that watches for a move keeps watching afterwards.
     *
     * A guard on the design rather than a reproduction of one bug: whatever a fetch fails on,
     * it has to come back as a value, because a throw reaching this collector would end it and
     * the app would carry on running having quietly stopped noticing that the user had moved —
     * no crash, no log, and nothing short of a restart to bring it back.
     */
    @Test
    fun `a move whose fetch fails does not stop the next move being noticed`() = runTest {
        stubRecentHistory()
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } throws IOException("offline")

        settings.value = AppSettings(location = KATHMANDU)
        advanceUntilIdle()

        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        settings.value = AppSettings(location = REYKJAVIK)
        advanceUntilIdle()

        coVerify {
            forecastApi.getForecast(REYKJAVIK.lat, REYKJAVIK.lon, timezone = REYKJAVIK.timezone)
        }
    }

    /**
     * A move waits out the fetch it is replacing rather than cancelling it and pressing on.
     *
     * Cancellation is only noticed where a coroutine suspends, and the write is the one part of
     * a fetch that cannot be left half done — Room's transaction runs to its end. So a fetch
     * let go of mid-write carries on writing, and what it writes is the city the user has just
     * left, on top of the readings that replaced it.
     *
     * The uninterruptible write is what the [NonCancellable] stub stands in for: without it the
     * old fetch unwinds at the first suspension either way and the test says nothing about the
     * waiting. With it, a move that only cancelled would write Kathmandu first and let Berlin
     * land on top.
     */
    @Test
    fun `a move waits for the fetch it supersedes to finish writing`() = runTest {
        stubRecentHistory()
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()

        val berlinWriteReached = CompletableDeferred<Unit>()
        val releaseBerlinWrite = CompletableDeferred<Unit>()
        val writes = Collections.synchronizedList(mutableListOf<String>())

        coEvery { dao.replaceSeries(any(), any(), any()) } coAnswers {
            withContext(NonCancellable) {
                berlinWriteReached.complete(Unit)
                releaseBerlinWrite.await()
                writes.add("berlin")
            }
        }
        coEvery { dao.replaceAllReadings(any(), any()) } coAnswers { writes.add("kathmandu") }

        val ordinary = async { repository.refresh() }
        berlinWriteReached.await()

        // Cancels the Berlin fetch, which is inside a write it cannot be pulled out of. The
        // collector suspends on the wait rather than pressing on, so this returns and the test
        // is free to let that write finish.
        settings.value = AppSettings(location = KATHMANDU)

        releaseBerlinWrite.complete(Unit)
        ordinary.await()
        advanceUntilIdle()

        assertEquals(listOf("berlin", "kathmandu"), writes.toList())
    }
}
