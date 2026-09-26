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
import kotlinx.coroutines.flow.filter
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
 * How the last fetch ended, or that one is still running.
 * Lets a screen with no readings explain why, instead of guessing.
 */
enum class RefreshState {

    /**
     * A fetch is running, or none has finished yet. Also returned when the joined
     * fetch was superseded by one for a new location that is still running.
     */
    InFlight,

    /** The series was fetched and stored. */
    Updated,

    /**
     * The fetch worked but carried no readings, so nothing was stored.
     * Distinct from [Updated] because Room delivers stored rows later, so an
     * empty table right after [Updated] can still just be a load in progress.
     */
    NoReadings,

    /** No location is set, so there was nothing to fetch for. */
    NoLocation,

    /** The fetch failed. Whatever was stored before it is untouched. */
    Failed
}

/**
 * What the stored series depends on. The location name is just a display label,
 * so renaming it alone shouldn't trigger a refetch; lat/lon/timezone should.
 */
private data class SeriesLocation(val lat: Double, val lon: Double, val timezone: String)

private const val TAG = "PressureRepo"

/**
 * How far back the forecast endpoint returns history on its own (`past_days=30`).
 * Anything older has to come from the archive API instead.
 */
private const val FORECAST_HISTORY_DAYS = 30L

/** How far back to reach the first time, when there is no stored history to fill a gap in. */
private const val INITIAL_BACKFILL_DAYS = 60L

/** A forecast older than this is worth refetching. Open-Meteo publishes hourly. */
private const val FORECAST_FRESHNESS_HOURS = 1L

/**
 * [runCatching] but rethrows [CancellationException] instead of swallowing it.
 * Otherwise a fetch cancelled by a location change would be logged as a failure
 * and could keep running to write stale results over the replacement's.
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
    // Pinned to ROOT: these go into an Open-Meteo query string, not onto a screen.
    // A device locale with non-Latin digits would otherwise send a date the API can't parse.
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    // Guards [inFlight] only. The fetch itself runs outside the lock, so joining an existing
    // refresh never waits on the network — it waits on the Deferred.
    private val refreshMutex = Mutex()

    /** The refresh in progress, or null when none is. Read and replaced under [refreshMutex]. */
    private var inFlight: Deferred<RefreshState>? = null

    private val _refreshState = MutableStateFlow(RefreshState.InFlight)

    /**
     * How the fetching is going, for screens that need to explain an empty series.
     * Published as a flow since refreshes are often triggered by something other
     * than the screen observing them (the hourly worker, a location change).
     */
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    init {
        observeLocationChanges()
    }

    fun getReadingsInRange(from: Instant, to: Instant): Flow<List<PressureReading>> =
        dao.getReadingsInRange(from, to)

    /**
     * Fetches the current series and stores it, or joins the fetch already running.
     * Callers overlap by design, so joining avoids races between concurrent fetches.
     * Runs in [scope], not the caller's, so a closed screen can't cancel others waiting on it.
     */
    suspend fun refresh(): RefreshState = fetch(RefreshMode.KeepHistory)

    private suspend fun fetch(mode: RefreshMode): RefreshState {
        val running = refreshMutex.withLock {
            // On a location change, wait for the old fetch to fully cancel rather than just
            // signal it, so it can't sneak in a write with stale readings afterwards.
            if (mode == RefreshMode.ReplaceEverything) {
                inFlight?.cancelAndJoin()
                inFlight = null
            }
            inFlight?.takeIf { it.isActive } ?: startFetch(mode).also { inFlight = it }
        }

        return try {
            running.await()
        } catch (e: CancellationException) {
            // The shared fetch was superseded by a new one; only rethrow if this
            // caller itself was cancelled, not because the fetch it joined was.
            currentCoroutineContext().ensureActive()
            RefreshState.InFlight
        }
    }

    /**
     * Starts a fetch and publishes what becomes of it; caller records it as the one in flight.
     * Publishes [RefreshState.InFlight] before dispatching so a state read right after can't
     * see a previous fetch's stale result. A cancelled fetch publishes nothing.
     */
    private fun startFetch(mode: RefreshMode): Deferred<RefreshState> {
        _refreshState.value = RefreshState.InFlight
        return scope.async { fetchAndStore(mode).also { _refreshState.value = it } }
    }

    /**
     * Refetches whenever the stored location changes, including the first one at onboarding.
     * Lives here rather than in whoever writes the location, since owning the stored
     * series means owning the reaction to it changing.
     */
    private fun observeLocationChanges() {
        scope.launch {
            prefs.settings
                .map { SeriesLocation(it.location.lat, it.location.lon, it.location.timezone) }
                .distinctUntilChanged()
                // The location active at startup is where the stored data already is.
                .drop(1)
                // Filters out the zero/zero default (a failed settings read, not a real move).
                // Placed after drop(1) so onboarding's first real location still counts.
                .filter { it.lat != 0.0 || it.lon != 0.0 }
                .collect { fetch(RefreshMode.ReplaceEverything) }
        }
    }

    /**
     * Runs in [scope] with no dispatcher of its own; wrapping would only hide the thread.
     * Reports failure instead of throwing: a throw here would kill the location collector,
     * silently breaking future refreshes. Cancellation still propagates; see [catchingFailures].
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

        // Own catch, not part of the outcome: the forecast endpoint already covers 30 days,
        // so failing to backfill further just thins the chart, it isn't a refresh failure.
        if (mode == RefreshMode.KeepHistory) {
            catchingFailures { gapFillIfNeeded(loc, timezone, now) }
                .onFailure { Log.e(TAG, "Archive fetch failed", it) }
        }

        return catchingFailures { storeForecast(mode, loc, timezone, now) }
            .onFailure { Log.e(TAG, "Forecast fetch failed", it) }
            .getOrElse { RefreshState.Failed }
    }

    /**
     * Fetches history older than the forecast endpoint covers, when a gap exists.
     * Never called on a location move: old history belongs to the old place and
     * gets replaced anyway, so the new location just keeps its 30 forecast days.
     */
    private suspend fun gapFillIfNeeded(loc: LocationData, timezone: String, now: Instant) {
        val lastHistorical = dao.getLatestHistorical(now)
        val historyStart = now.minus(FORECAST_HISTORY_DAYS, ChronoUnit.DAYS)
        if (lastHistorical != null && !lastHistorical.dateTime.isBefore(historyStart)) return

        val gapStart = lastHistorical?.dateTime ?: now.minus(INITIAL_BACKFILL_DAYS, ChronoUnit.DAYS)
        Log.d(TAG, "Fetching archive from ${formatDate(gapStart, timezone)} to ${formatDate(historyStart, timezone)}")

        val response = archiveApi.getArchive(
            latitude = loc.lat,
            longitude = loc.lon,
            startDate = formatDate(gapStart, timezone),
            endDate = formatDate(historyStart, timezone),
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

        // Not written: both write paths clear what they replace first, so an empty response
        // would wipe the forecast (or the whole table on a move) instead of just updating it.
        if (readings.isEmpty()) {
            Log.w(TAG, "Forecast response carried no readings; leaving the stored series alone")
            return RefreshState.NoReadings
        }

        val historical = readings.filter { it.dateTime.isBefore(now) }
        val forecast = readings.filter { !it.dateTime.isBefore(now) }

        Log.d(TAG, "Inserting ${historical.size} historical and ${forecast.size} forecast readings")

        // Last chance to drop a superseded fetch before it writes; the DB writes below
        // don't suspend, so they wouldn't otherwise notice a cancellation in time.
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

    /** No dispatcher of its own: Room already runs suspending queries on its own executor. */
    suspend fun isForecastStale(): Boolean {
        val now = Instant.now()
        val fetchedAt = dao.getLatestForecastFetchTime(now) ?: return true
        return fetchedAt.isBefore(now.minus(FORECAST_FRESHNESS_HOURS, ChronoUnit.HOURS))
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
