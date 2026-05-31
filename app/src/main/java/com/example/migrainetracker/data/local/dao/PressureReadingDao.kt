package com.example.migrainetracker.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.migrainetracker.data.model.PressureReading
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface PressureReadingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistorical(readings: List<PressureReading>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForecast(readings: List<PressureReading>)

    @Query("DELETE FROM pressure_readings WHERE dateTime >= :from")
    suspend fun deleteForecast(from: Instant)

    @Query("SELECT * FROM pressure_readings WHERE dateTime BETWEEN :from AND :to ORDER BY dateTime ASC")
    fun getReadingsInRange(from: Instant, to: Instant): Flow<List<PressureReading>>

    @Query("SELECT * FROM pressure_readings WHERE dateTime < :now ORDER BY dateTime DESC LIMIT 1")
    suspend fun getLatestHistorical(now: Instant): PressureReading?

    @Query("SELECT * FROM pressure_readings WHERE dateTime >= :from AND dateTime < :to ORDER BY dateTime ASC")
    suspend fun getForecastReadings(from: Instant, to: Instant): List<PressureReading>

    @Query("SELECT fetchedDateTime FROM pressure_readings WHERE dateTime >= :now ORDER BY dateTime ASC LIMIT 1")
    suspend fun getLatestForecastFetchTime(now: Instant): Instant?

    @Query("SELECT * FROM pressure_readings ORDER BY dateTime ASC")
    fun getAllReadings(): Flow<List<PressureReading>>
}
