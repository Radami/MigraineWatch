package com.radami.migrainewatch.data.repository

import android.util.Log
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import com.radami.migrainewatch.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What a fetch does with the readings already stored. */
private enum class RefreshMode {

    /** Keeps the stored history and replaces the forecast. Every ordinary refresh. */
    KeepHistory,

    /** Discards the lot first: what is stored describes a place the user has left. */
    ReplaceEverything
}

/**
 * How the last fetch ended, or that one is still under way.
 *
 * A screen with no readings to draw cannot tell on its own why it has none, and the difference
 * matters to the reader: a fetch still running is worth waiting for, a failed one is worth
 * saying something about, and an empty table behind a fetch that succeeded is neither. The
 * repository is the only thing that knows, so it says so rather than leaving each screen to
 * guess from the shape of its own data.
 */
enum class RefreshState {

    /**
     * A fetch is running, or none has finished yet. Also what a caller is told when the fetch
     * it joined was superseded by one for a new location: that replacement is still running,
     * so nothing can be said about the result yet.
     */
    InFlight,

    /** The series was fetched and stored. */
    Updated,

    /** No location is set, so there was nothing to fetch for. */
    NoLocation,

    /** The fetch failed. Whatever was stored before it is untouched. */
    Failed
}

/**
 * What the stored series depends on. The name a location carries is a label for the user, so
 * two spellings of one place are not a reason to refetch; move any of these and the readings
 * describe somewhere else.
 */
private data class SeriesLocation(val lat: Double, val lon: Double, val timezone: String)

private const val TAG = "PressureRepo"

/**
 * [runCatching] with cancellation left alone.
 *
 * `runCatching` catches [Throwable], cancellation included, which would turn a fetch superseded
 * by a move into one that had merely failed: logged as an error, and — because the coroutine
 * carries on from the catch rather than unwinding — free to reach its own write afterwards,
 * over the readings that replaced it. Every part of a fetch runs where it can be cancelled, so
 * every catch here has to tell the two apart.
 */
private inline fun <T> catchingFailures(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

@Singleton
class PressureRepository @Inject constructor(
    private val dao: PressureReadingDao,
    private val forecastApi: OpenMeteoApi,
    private val archiveApi: OpenMeteoArchiveApi,
    private val prefs: UserPreferences,
    @ApplicationScope private val scope: CoroutineScope
) {
    // Pinned to ROOT because these go into an Open-Meteo query string, not onto a screen.
    // Without a locale they would follow the device, and a locale whose default numbering
    // system is not Latin — Arabic (Egypt), for one — renders the digits in its own script,
    // sending the API a date it cannot parse. The app is unusable on those devices.
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    // Guards [inFlight] only. The fetch itself runs outside the lock, so joining an existing
    // refresh never waits on the network — it waits on the Deferred.
    private val refreshMutex = Mutex()

    /** The refresh in progress, or null when none is. Read and replaced under [refreshMutex]. */
    private var inFlight: Deferred<RefreshState>? = null

    private val _refreshState = MutableStateFlow(RefreshState.InFlight)

    /**
     * How the fetching is going, for screens that have to explain an empty series.
     *
     * Published rather than returned only, because the fetch a screen cares about is often not
     * one it asked for: the hourly worker and a change of location both drive refreshes under
     * a screen that is already up.
     */
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    init {
        observeLocationChanges()
    }

    fun getReadingsInRange(from: Instant, to: Instant): Flow<List<PressureReading>> =
        dao.getReadingsInRange(from, to)

    /**
     * Fetches the current series and stores it, or joins the fetch already running.
     *
     * Callers overlap by design — the Today screen refreshes when it opens, the Pressure
     * screen when its data is stale, and the hourly worker whenever it fires — and separate
     * fetches racing each other write in whatever order they happen to finish, so the series
     * that lands last is not necessarily the one fetched last. Joining collapses them into a
     * single fetch with a single result, which is what every caller wanted anyway.
     *
     * The fetch runs in the repository's own [scope] rather than the caller's. A caller that
     * goes away mid-flight — a ViewModel whose screen closed — must not take the result with
     * it while the others are still waiting on it.
     */
    suspend fun refresh(): RefreshState = fetch(RefreshMode.KeepHistory)

    private suspend fun fetch(mode: RefreshMode): RefreshState {
        val running = refreshMutex.withLock {
            // A fetch for a place the user has left is not one to join, and it must not be
            // left to write on top of the one replacing it. Waited out rather than merely
            // cancelled: cancellation is only noticed at a suspension point, so a fetch let go
            // of here could still reach its own write — with the old city's readings — after
            // the replacement had made its.
            if (mode == RefreshMode.ReplaceEverything) {
                inFlight?.cancelAndJoin()
                inFlight = null
            }
            inFlight?.takeIf { it.isActive } ?: startFetch(mode)
        }

        return try {
            running.await()
        } catch (e: CancellationException) {
            // The shared fetch was superseded by one for a new location, which is now running.
            // That is not this caller failing, so rethrow only if the caller is the one that
            // has been cancelled.
            currentCoroutineContext().ensureActive()
            RefreshState.InFlight
        }
    }

    /**
     * Starts a fetch, records it as the one in flight, and publishes what becomes of it.
     *
     * Called under [refreshMutex], so neither [inFlight] nor the two writes to [_refreshState]
     * here can interleave with another fetch's.
     *
     * [RefreshState.InFlight] is published before the coroutine is dispatched rather than from
     * inside it, so a caller that starts a refresh and then reads the state cannot catch the
     * result of the previous one still standing.
     *
     * A cancelled fetch publishes nothing: [fetchAndStore] unwinds instead of returning, so the
     * fetch replacing it keeps the state it has just set.
     */
    private fun startFetch(mode: RefreshMode): Deferred<RefreshState> {
        _refreshState.value = RefreshState.InFlight
        return scope.async { fetchAndStore(mode).also { _refreshState.value = it } }
            .also { inFlight = it }
    }

    /**
     * Refetches when the user moves, because everything stored describes where they were.
     *
     * Observed here rather than done by whoever writes the location: the stored series belongs
     * to a place, so noticing that the place changed is this class's job, and a screen that
     * happens to save a location should not have to remember to say so. Onboarding is covered
     * by the same collector — the move from no location to the first one is a change like any
     * other.
     */
    private fun observeLocationChanges() {
        scope.launch {
            prefs.settings
                .map { SeriesLocation(it.location.lat, it.location.lon, it.location.timezone) }
                .distinctUntilChanged()
                // The location in force when the app starts is where the data already is.
                .drop(1)
                .collect { fetch(RefreshMode.ReplaceEverything) }
        }
    }

    /**
     * No dispatcher of its own: [scope] is the one this always runs in, and wrapping it again
     * would only hide which thread the work is on and put a second dispatch between the tests
     * and the code they are driving.
     *
     * Reports failure rather than throwing it. One of the callers waiting on this is the
     * location collector, and a throw reaching it would end the collector for the rest of the
     * process: the app would go on running, quietly never noticing that the user had moved
     * again. Cancellation is the one thing still allowed through — see [catchingFailures].
     */
    private suspend fun fetchAndStore(mode: RefreshMode): RefreshState {
        val loc = catchingFailures { prefs.settings.first().location }
            .onFailure { Log.e(TAG, "Could not read the stored location", it) }
            .getOrElse { return RefreshState.Failed }

        if (loc.lat == 0.0 && loc.lon == 0.0) {
            Log.w(TAG, "Refresh skipped: No location set")
            return RefreshState.NoLocation
        }

        val timezone = loc.timezone.ifBlank { ZoneId.systemDefault().id }
        val now = Instant.now()
        Log.d(TAG, "Refreshing data for ${loc.name} at ${loc.lat},${loc.lon} in $timezone")

        // Its own catch, and not part of the outcome: the forecast endpoint returns 30 days of
        // history by itself, so failing to reach further back than that is a thinner chart
        // rather than a refresh that failed.
        if (mode == RefreshMode.KeepHistory) {
            catchingFailures { gapFillIfNeeded(loc, timezone, now) }
                .onFailure { Log.e(TAG, "Archive fetch failed", it) }
        }

        return catchingFailures { storeForecast(mode, loc, timezone, now) }
            .onFailure { Log.e(TAG, "Forecast fetch failed", it) }
            .getOrElse { RefreshState.Failed }
    }

    /**
     * Fetches the stretch of history the forecast endpoint does not reach back to, when the
     * newest stored reading is old enough for there to be one.
     *
     * Never called on a move: the stored history is then the old city's, so it says nothing
     * about what is missing for the new one — and the replacement in [storeForecast] would
     * delete it regardless. The new location keeps the 30 days the forecast endpoint returns.
     */
    private suspend fun gapFillIfNeeded(loc: LocationData, timezone: String, now: Instant) {
        val lastHistorical = dao.getLatestHistorical(now)
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        if (lastHistorical != null && !lastHistorical.dateTime.isBefore(thirtyDaysAgo)) return

        val gapStart = lastHistorical?.dateTime ?: now.minus(60, ChronoUnit.DAYS)
        Log.d(TAG, "Fetching archive from ${formatDate(gapStart, timezone)} to ${formatDate(thirtyDaysAgo, timezone)}")

        val response = archiveApi.getArchive(
            latitude = loc.lat,
            longitude = loc.lon,
            startDate = formatDate(gapStart, timezone),
            endDate = formatDate(thirtyDaysAgo, timezone),
            timezone = timezone
        )
        val fetchedAt = Instant.now()
        val historical = parseResponse(response, fetchedAt, timezone)
            .filter { it.dateTime.isBefore(now) }

        Log.d(TAG, "Inserting ${historical.size} historical readings from archive")
        dao.insertHistorical(historical)
    }

    /** The regular fetch: past_days=30 plus the 7-day forecast, stored as one write. */
    private suspend fun storeForecast(
        mode: RefreshMode,
        loc: LocationData,
        timezone: String,
        now: Instant
    ): RefreshState {
        Log.d(TAG, "Fetching forecast for timezone $timezone")
        val response = forecastApi.getForecast(
            latitude = loc.lat,
            longitude = loc.lon,
            timezone = timezone
        )
        val fetchedAt = Instant.now()
        val readings = parseResponse(response, fetchedAt, response.timezone.ifBlank { timezone })

        val historical = readings.filter { it.dateTime.isBefore(now) }
        val forecast = readings.filter { !it.dateTime.isBefore(now) }

        Log.d(TAG, "Inserting ${historical.size} historical and ${forecast.size} forecast readings")

        // The last chance to drop a superseded fetch before it writes. Everything above this
        // point suspends on the network, where a cancellation is noticed immediately; the
        // writes below need not reach a check of their own before the rows are in.
        currentCoroutineContext().ensureActive()

        // One transaction either way: the screens observe this table, and a series briefly
        // emptied and not yet rewritten reads to them as one that never arrived.
        when (mode) {
            RefreshMode.KeepHistory ->
                dao.replaceSeries(historical = historical, forecastFrom = now, forecast = forecast)

            RefreshMode.ReplaceEverything ->
                dao.replaceAllReadings(historical = historical, forecast = forecast)
        }

        return RefreshState.Updated
    }

    /**
     * No dispatcher of its own, as nothing else here has one: Room runs a suspending query on
     * its own executor, so wrapping this only hid which thread the work was really on.
     */
    suspend fun isForecastStale(): Boolean {
        val now = Instant.now()
        val fetchedAt = dao.getLatestForecastFetchTime(now) ?: return true
        val oneHourAgo = now.minus(1, ChronoUnit.HOURS)
        return fetchedAt.isBefore(oneHourAgo)
    }

    private fun parseResponse(
        response: OpenMeteoResponse,
        fetchedAt: Instant,
        timezone: String
    ): List<PressureReading> {
        val zone = ZoneId.of(timezone)
        return response.hourly.time.mapIndexedNotNull { i, timeStr ->
            val pressureMsl = response.hourly.pressureMsl.getOrNull(i) ?: return@mapIndexedNotNull null
            val surfacePressure = response.hourly.surfacePressure.getOrNull(i) ?: return@mapIndexedNotNull null
            val instant = LocalDateTime.parse(timeStr, isoFormatter)
                .atZone(zone)
                .toInstant()
            PressureReading(instant, pressureMsl, surfacePressure, fetchedAt)
        }
    }

    private fun formatDate(instant: Instant, timezone: String): String =
        instant.atZone(ZoneId.of(timezone)).toLocalDate().format(dateFormatter)
}
