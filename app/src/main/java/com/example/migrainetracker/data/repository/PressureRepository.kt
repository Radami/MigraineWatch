package com.example.migrainetracker.data.repository

import android.util.Log
import com.example.migrainetracker.data.local.dao.PressureReadingDao
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.remote.OpenMeteoApi
import com.example.migrainetracker.data.remote.OpenMeteoArchiveApi
import com.example.migrainetracker.data.remote.dto.OpenMeteoResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PressureRepository @Inject constructor(
    private val dao: PressureReadingDao,
    private val forecastApi: OpenMeteoApi,
    private val archiveApi: OpenMeteoArchiveApi,
    private val prefs: UserPreferences
) {
    private val isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun getReadingsInRange(from: Instant, to: Instant): Flow<List<PressureReading>> =
        dao.getReadingsInRange(from, to)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val settings = prefs.settings.first()
        val loc = settings.location
        if (loc.lat == 0.0 && loc.lon == 0.0) {
            Log.w("PressureRepo", "Refresh skipped: No location set")
            return@withContext
        }

        val timezone = loc.timezone.ifBlank { ZoneId.systemDefault().id }
        val now = Instant.now()
        Log.d("PressureRepo", "Refreshing data for ${loc.name} at ${loc.lat},${loc.lon} in $timezone")

        // Gap fill: if most recent historical row is older than 30 days
        val lastHistorical = dao.getLatestHistorical(now)
        val thirtyDaysAgo = now.minus(30, ChronoUnit.DAYS)
        if (lastHistorical == null || lastHistorical.dateTime.isBefore(thirtyDaysAgo)) {
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
            val readings = parseResponse(response, fetchedAt, timezone)

            val historical = readings.filter { it.dateTime.isBefore(now) }
            val forecast = readings.filter { !it.dateTime.isBefore(now) }
            
            Log.d("PressureRepo", "Inserting ${historical.size} historical and ${forecast.size} forecast readings")

            dao.insertHistorical(historical)
            dao.deleteForecast(now)
            dao.insertForecast(forecast)
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
