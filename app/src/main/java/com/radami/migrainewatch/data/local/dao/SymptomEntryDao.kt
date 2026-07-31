package com.radami.migrainewatch.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.radami.migrainewatch.data.model.SymptomEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface SymptomEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SymptomEntry)

    @Query("SELECT * FROM symptom_entries WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: LocalDate): SymptomEntry?

    @Query("SELECT * FROM symptom_entries WHERE date BETWEEN :from AND :to ORDER BY date DESC")
    fun getEntriesInRange(from: LocalDate, to: LocalDate): Flow<List<SymptomEntry>>

    @Query("SELECT * FROM symptom_entries ORDER BY date DESC")
    fun getAllEntries(): Flow<List<SymptomEntry>>

    @Query("SELECT COUNT(*) FROM symptom_entries")
    fun getTotalCount(): Flow<Int>

    @Query("SELECT MIN(date) FROM symptom_entries")
    suspend fun getEarliestDate(): String?

    @Query("DELETE FROM symptom_entries WHERE date = :date")
    suspend fun deleteByDate(date: LocalDate)
}
