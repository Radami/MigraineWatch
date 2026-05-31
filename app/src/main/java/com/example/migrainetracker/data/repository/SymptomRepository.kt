package com.example.migrainetracker.data.repository

import com.example.migrainetracker.data.local.dao.SymptomEntryDao
import com.example.migrainetracker.data.model.SymptomEntry
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SymptomRepository @Inject constructor(
    private val dao: SymptomEntryDao
) {
    fun getEntriesInRange(from: LocalDate, to: LocalDate): Flow<List<SymptomEntry>> =
        dao.getEntriesInRange(from, to)

    fun getAllEntries(): Flow<List<SymptomEntry>> = dao.getAllEntries()

    fun getTotalCount(): Flow<Int> = dao.getTotalCount()

    suspend fun getByDate(date: LocalDate): SymptomEntry? = dao.getByDate(date)

    suspend fun save(entry: SymptomEntry) = dao.upsert(entry)

    suspend fun getEarliestDate(): LocalDate? =
        dao.getEarliestDate()?.let { LocalDate.parse(it) }
}
