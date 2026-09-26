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

        /** 45 min off Berlin's hourly grid, so REPLACE would collide with nothing. */
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

    // Unconfined so a started fetch runs inline to its first suspension, letting a test say
    // "the fetch is in flight" without waiting on a clock.
    private val refreshScope = CoroutineScope(UnconfinedTestDispatcher())

    /** Mutable so a test can move the user; stubbed before construction since the watch starts then. */
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

    /** A response that parsed to nothing: the shape a location with no series comes back in. */
    private fun emptyResponse() = response().copy(
        hourly = HourlyData(time = emptyList(), pressureMsl = emptyList(), surfacePressure = emptyList())
    )

    /** Skips the archive gap fill (triggered when stored history is over 30 days old). */
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
     * Must replace the forecast in one transaction: a separate delete+insert lets an observer
     * see a torn series, which Today reports as a failed load. Asserted on the calls, not on
     * what an observer saw, since the tear itself is a race a behavioral test could miss.
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
     * Multiple screens/worker can call refresh at once; racing fetches could write out of
     * order. A caller arriving mid-fetch must join it, not start a competing one.
     */
    @Test
    fun `overlapping refreshes share one fetch`() = runTest {
        val fetches = AtomicInteger()

        // Held open so every caller is inside the repository at once, or the first fetch could
        // finish before the second caller even arrives.
        val releaseFetch = CompletableDeferred<Unit>()
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } coAnswers {
            fetches.incrementAndGet()
            releaseFetch.await()
            response()
        }
        stubRecentHistory()

        // Uses the test scope's own async, not backgroundScope, so advanceUntilIdle drives it.
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

    /** Stored readings belong to a place, so the repository itself notices a move and refetches. */
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
     * Must discard old rows, not overlay the new city: Berlin and Kathmandu grids never agree,
     * so leftover rows would interleave into a bogus series. Asserted on calls, not observed
     * state, for the same reason as the atomicity test above.
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

    /** The name is just a label, not part of what was fetched; a rename describes the same readings. */
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

    /**
     * A store that can't be read reports the defaults (no location) rather than throwing.
     * Read as a move, a disk error would wrongly send a fetch for a nonexistent place.
     */
    @Test
    fun `settings falling back to no location is not treated as a move`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        repository.refresh()
        assertEquals(RefreshState.Updated, repository.refreshState.value)

        settings.value = AppSettings()
        advanceUntilIdle()

        // If acted on, this would cancel any in-flight fetch and settle on NoLocation, making
        // the card ask for a location that's actually set fine.
        assertEquals(RefreshState.Updated, repository.refreshState.value)
    }

    /** And the watch is still watching once the store can be read again. */
    @Test
    fun `a move after a failed settings read is still noticed`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        settings.value = AppSettings()
        advanceUntilIdle()

        settings.value = AppSettings(location = KATHMANDU)
        advanceUntilIdle()

        coVerify {
            forecastApi.getForecast(KATHMANDU.lat, KATHMANDU.lon, timezone = KATHMANDU.timezone)
        }
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
     * A network failure must come back as a value, not a throw: thrown, it would reach the
     * worker (reported as false success), a ViewModel init (crash), or kill the location
     * collector permanently.
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
     * The state a screen watches has to move when the fetching does, not only when it ends: a
     * card reading it in the middle of a fetch would otherwise be told about the fetch before.
     */
    @Test
    fun `a refresh reports itself in flight before settling on its outcome`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()
        stubRecentHistory()

        repository.refresh()
        assertEquals(RefreshState.Updated, repository.refreshState.value)

        // Held open so the second refresh can be caught while it is still out.
        val releaseFetch = CompletableDeferred<Unit>()
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } coAnswers {
            releaseFetch.await()
            response()
        }

        val second = async { repository.refresh() }
        advanceUntilIdle()
        assertEquals(RefreshState.InFlight, repository.refreshState.value)

        releaseFetch.complete(Unit)
        second.await()
        assertEquals(RefreshState.Updated, repository.refreshState.value)
    }

    /**
     * An empty response must be reported, not stored: replaceSeries still deletes the old
     * forecast even when it has nothing to insert, so storing this would erase good data.
     */
    @Test
    fun `a response with no readings is reported and not written`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns emptyResponse()
        stubRecentHistory()

        assertEquals(RefreshState.NoReadings, repository.refresh())

        coVerify(exactly = 0) { dao.replaceSeries(any(), any(), any()) }
        coVerify(exactly = 0) { dao.deleteForecast(any()) }
    }

    /** And a move with nothing to move to leaves the old city's readings rather than the table empty. */
    @Test
    fun `a move whose response is empty does not clear the stored series`() = runTest {
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns emptyResponse()
        stubRecentHistory()

        settings.value = AppSettings(location = KATHMANDU)
        advanceUntilIdle()

        coVerify(exactly = 0) { dao.replaceAllReadings(any(), any()) }
        coVerify(exactly = 0) { dao.deleteAllReadings() }
    }

    /**
     * This DAO call used to sit outside every catch, so a Room failure here threw out of the
     * fetch entirely. Refresh still succeeds: this call is only for the archive gap fill.
     */
    @Test
    fun `a storage failure on the way to the network is not thrown out of refresh`() = runTest {
        coEvery { dao.getLatestHistorical(any()) } throws IllegalStateException("database closed")
        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response()

        assertEquals(RefreshState.Updated, repository.refresh())
        coVerify(exactly = 1) { dao.replaceSeries(any(), any(), any()) }
    }

    /**
     * The location collector must keep watching after a failed fetch. A throw reaching it would
     * end it silently, and the app would quietly stop noticing moves until restarted.
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
     * A move must wait out the fetch it replaces, not just cancel it: Room's write can't be
     * interrupted mid-transaction, so a cancelled-but-still-writing fetch could land the old
     * city's data on top of the new one. [NonCancellable] simulates that uninterruptible write.
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

        // Cancels the Berlin fetch mid-write; the collector suspends waiting for it to finish.
        settings.value = AppSettings(location = KATHMANDU)

        releaseBerlinWrite.complete(Unit)
        ordinary.await()
        advanceUntilIdle()

        assertEquals(listOf("berlin", "kathmandu"), writes.toList())
    }
}
