package com.example.migrainetracker.data.repository

import com.example.migrainetracker.data.local.dao.SymptomEntryDao
import com.example.migrainetracker.data.model.Severity
import com.example.migrainetracker.data.model.SymptomEntry
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SymptomRepositoryTest {

    private val dao = mockk<SymptomEntryDao>(relaxed = true)
    private val repository = SymptomRepository(dao)

    private val date = LocalDate.parse("2026-07-13")

    @Test
    fun `delete removes the entry for that date only`() = runTest {
        repository.delete(date)

        coVerify(exactly = 1) { dao.deleteByDate(date) }
    }

    @Test
    fun `save upserts so editing a day replaces its entry`() = runTest {
        val now = Instant.now()
        val entry = SymptomEntry(
            date = date,
            severity = Severity.MILD,
            triggers = listOf("Stress"),
            durationBucket = null,
            reliefPercent = null,
            medication = null,
            notes = null,
            createdAt = now,
            updatedAt = now
        )

        repository.save(entry)

        coVerify(exactly = 1) { dao.upsert(entry) }
    }
}
