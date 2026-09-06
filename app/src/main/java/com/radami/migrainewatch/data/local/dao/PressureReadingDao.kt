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

    // REPLACE so a refresh rewrites every hour it fetched and the stored series always comes
    // from a single fetch. With IGNORE, historical rows from earlier runs survived and got
    // stitched to fresh forecast data — the mock re-anchors its pattern to "now" on every
    // fetch, so runs on previous days left behind an inconsistent series.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistorical(readings: List<PressureReading>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(readings: List<PressureReading>)

    @Query("DELETE FROM pressure_readings WHERE dateTime >= :from")
    suspend fun deleteForecast(from: Instant)

    /**
     * Writes one fetch's whole series at once: the history it measured, then the forecast,
     * replacing everything from [forecastFrom] on.
     *
     * One transaction rather than three calls because [getReadingsInRange] is observed by a
     * live screen. Run apart, the delete and the insert are separately visible, and an
     * observer that reads between them gets a series with its forecast gone — which the
     * Today screen cannot tell from a forecast that never arrived, so it reports a failed
     * load over data that was about to land. Folding the historical insert in too leaves the
     * refresh with a single invalidation, so the screen redraws once for the whole fetch.
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
     * Replaces the stored series outright, history included, for a fetch whose readings
     * describe somewhere else.
     *
     * [replaceSeries] cannot do this. It leans on REPLACE to overwrite the history it refetches,
     * which only works while the old and new rows share timestamps — true between two fetches
     * for one place, false the moment the place changes. Readings are stored as instants and
     * both APIs report hourly on the hour in *local* time, so a move from Berlin to Kathmandu
     * puts the new series on a grid 45 minutes off the old one: nothing collides, nothing is
     * overwritten, and the two cities' readings end up interleaved in one table. Rows older
     * than the 30 days a forecast fetch covers survive a move regardless of the timezone.
     *
     * One transaction for the same reason as [replaceSeries]: a screen reading between the
     * delete and the insert would find an empty series and report a failed load.
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
