package com.radami.migrainewatch.data.repository

import android.util.Log
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import com.radami.migrainewatch.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * What the stored series depends on. The name a location carries is a label for the user, so
 * two spellings of one place are not a reason to refetch; move any of these and the readings
 * describe somewhere else.
 */
private data class SeriesLocation(val lat: Double, val lon: Double, val timezone: String)

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
    private var inFlight: Deferred<Unit>? = null

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
    suspend fun refresh() = fetch(RefreshMode.KeepHistory)

    private suspend fun fetch(mode: RefreshMode) {
        val running = refreshMutex.withLock {
            // A fetch for a place the user has left is not one to join, and it must not be
            // left to write on top of the one replacing it.
            if (mode == RefreshMode.ReplaceEverything) {
                inFlight?.cancel()
                inFlight = null
            }
            inFlight?.takeIf { it.isActive }
                ?: scope.async { fetchAndStore(mode) }.also { inFlight = it }
        }

        try {
            running.await()
        } catch (e: CancellationException) {
            // The shared fetch was superseded, which is not this caller failing. Rethrow only
            // if the caller is the one that has been cancelled.
            currentCoroutineContext().ensureActive()
        }
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
     */
    private suspend fun fetchAndStore(mode: RefreshMode) {
        val settings = prefs.settings.first()
        val loc = settings.location
        if (loc.lat == 0.0 && loc.lon == 0.0) {
            Log.w("PressureRepo", "Refresh skipped: No location set")
            return
        }

        val timezone = loc.timezone.ifBlank { ZoneId.systemDefault().id }
        val now = Instant.now()
        Log.d("PressureRepo", "Refreshing data for ${loc.name} at ${loc.lat},${loc.lon} in $timezone")

        // Gap fill: if most recent historical row is older than 30 days. Skipped on a move,
        // where the stored history is the old city's and so says nothing about what is missing
        // for the new one — and would be deleted by the replacement below anyway. The new
        // location keeps the 30 days the forecast endpoint returns.
        val lastHistorical = dao.getLatestHistorical(now)
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        val needsGapFill = mode == RefreshMode.KeepHistory &&
            (lastHistorical == null || lastHistorical.dateTime.isBefore(thirtyDaysAgo))
        if (needsGapFill) {
            val gapStart = lastHistorical?.dateTime ?: now.minus(60, ChronoUnit.DAYS)
            runCatching {
                Log.d("PressureRepo", "Fetching archive from ${formatDate(gapStart, timezone)} to ${formatDate(thirtyDaysAgo, timezone)}")
                val response = archiveApi.getArchive(
                    latitude = loc.lat,
                    longitude = loc.lon,
                    startDate = formatDate(gapStart, timezone),
                    endDate = formatDate(thirtyDaysAgo, timezone),
                    timezone = timezone
                )
                val fetchedAt = Instant.now()
                val readings = parseResponse(response, fetchedAt, timezone)
                val historical = readings.filter { it.dateTime.isBefore(now) }
                Log.d("PressureRepo", "Inserting ${historical.size} historical readings from archive")
                dao.insertHistorical(historical)
            }.onFailure { Log.e("PressureRepo", "Archive fetch failed", it) }
        }

        // Regular fetch: past_days=30 + 7-day forecast
        runCatching {
            Log.d("PressureRepo", "Fetching forecast for timezone $timezone")
            val response = forecastApi.getForecast(
                latitude = loc.lat,
                longitude = loc.lon,
                timezone = timezone
            )
            val fetchedAt = Instant.now()
            val readings = parseResponse(response, fetchedAt, response.timezone.ifBlank { timezone })

            val historical = readings.filter { it.dateTime.isBefore(now) }
            val forecast = readings.filter { !it.dateTime.isBefore(now) }
            
            Log.d("PressureRepo", "Inserting ${historical.size} historical and ${forecast.size} forecast readings")

            // One transaction either way: the screens observe this table, and a series briefly
            // emptied and not yet rewritten reads to them as one that never arrived.
            when (mode) {
                RefreshMode.KeepHistory ->
                    dao.replaceSeries(historical = historical, forecastFrom = now, forecast = forecast)

                RefreshMode.ReplaceEverything ->
                    dao.replaceAllReadings(historical = historical, forecast = forecast)
            }
        }.onFailure { Log.e("PressureRepo", "Forecast fetch failed", it) }
    }

    suspend fun isForecastStale(): Boolean = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val fetchedAt = dao.getLatestForecastFetchTime(now) ?: return@withContext true
        val oneHourAgo = now.minus(1, ChronoUnit.HOURS)
        fetchedAt.isBefore(oneHourAgo)
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
