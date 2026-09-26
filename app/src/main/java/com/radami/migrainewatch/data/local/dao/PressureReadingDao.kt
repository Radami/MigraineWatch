package com.radami.migrainewatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.radami.migrainewatch.data.model.PressureReading
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PressureReadingDao {

    // REPLACE so a refresh rewrites every hour it fetched, keeping the stored series from
    // a single fetch. IGNORE let stale rows from earlier runs survive and mix with fresh data.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistorical(readings: List<PressureReading>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(readings: List<PressureReading>)

    @Query("DELETE FROM pressure_readings WHERE dateTime >= :from")
    suspend fun deleteForecast(from: Instant)

    /**
     * Writes one fetch's whole series at once: history, then forecast from [forecastFrom] on.
     * One transaction because [getReadingsInRange] is observed live; split into separate
     * calls, a reader could catch the series with its forecast deleted but not yet reinserted.
     */
    @Transaction
    suspend fun replaceSeries(
        historical: List<PressureReading>,
        forecastFrom: Instant,
        forecast: List<PressureReading>
    ) {
        insertHistorical(historical)
        deleteForecast(forecastFrom)
        insertForecast(forecast)
    }

    @Query("DELETE FROM pressure_readings")
    suspend fun deleteAllReadings()

    /**
     * Replaces the stored series outright, history included, for a fetch describing a new place.
     * [replaceSeries]'s REPLACE-based overwrite only works when old and new rows share
     * timestamps, which a timezone change can break (e.g. Berlin to Kathmandu's 45-min offset).
     */
    @Transaction
    suspend fun replaceAllReadings(
        historical: List<PressureReading>,
        forecast: List<PressureReading>
    ) {
        deleteAllReadings()
        insertHistorical(historical)
        insertForecast(forecast)
    }

    @Query("SELECT * FROM pressure_readings WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime ASC")
    fun getReadingsInRange(from: Instant, to: Instant): Flow<List<PressureReading>>

    @Query("SELECT * FROM pressure_readings WHERE dateTime < :now ORDER BY dateTime DESC LIMIT 1")
    suspend fun getLatestHistorical(now: Instant): PressureReading?

    @Query("SELECT fetchedDateTime FROM pressure_readings WHERE dateTime >= :now ORDER BY dateTime ASC LIMIT 1")
    suspend fun getLatestForecastFetchTime(now: Instant): Instant?

    @Query("SELECT * FROM pressure_readings ORDER BY dateTime ASC")
    fun getAllReadings(): Flow<List<PressureReading>>
}
