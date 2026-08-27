package com.radami.migrainewatch.ui.screens.calendar

import androidx.lifecycle.SavedStateHandle
import com.radami.migrainewatch.data.model.Severity
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.repository.SymptomRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Relief, which the log entry flow records as a short list of chips rather than a slider.
 *
 * The distinction the tests exist for is 0% against no answer: a chip list makes "the
 * medication did nothing" and "I did not say" one tap apart, and storing the first as the
 * second — or the second as a zero — loses the only part of the entry a user could disagree
 * with later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogEntryViewModelTest {

    private companion object {
        val DATE: LocalDate = LocalDate.of(2026, 8, 23)
    }

    private val symptomRepository = mockk<SymptomRepository>(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { symptomRepository.getByDate(any()) } returns null
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = LogEntryViewModel(
        SavedStateHandle(mapOf("date" to DATE.toString())),
        symptomRepository
    )

    @Test
    fun `relief is unanswered until a chip is tapped`() {
        assertNull(viewModel().uiState.value.reliefPercent)
    }

    @Test
    fun `tapping a chip records that percentage`() {
        val viewModel = viewModel()

        viewModel.setRelief(75)

        assertEquals(75, viewModel.uiState.value.reliefPercent)
    }

    @Test
    fun `tapping the selected chip clears the field rather than recording zero`() {
        val viewModel = viewModel()
        viewModel.setRelief(50)

        viewModel.setRelief(null)

        assertNull(viewModel.uiState.value.reliefPercent)
    }

    @Test
    fun `zero percent is a real answer and survives to storage`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectSeverity(Severity.MILD)
        viewModel.setRelief(0)

        viewModel.save()
        advanceUntilIdle()

        val saved = slot<SymptomEntry>()
        coVerify { symptomRepository.save(capture(saved)) }
        assertEquals(0, saved.captured.reliefPercent)
    }

    @Test
    fun `an unanswered relief is stored as unanswered, not as zero`() = runTest {
        val viewModel = viewModel()
        advanceUntilIdle()
        viewModel.selectSeverity(Severity.MILD)

        viewModel.save()
        advanceUntilIdle()

        val saved = slot<SymptomEntry>()
        coVerify { symptomRepository.save(capture(saved)) }
        assertNull(saved.captured.reliefPercent)
    }

    @Test
    fun `every offered relief option is one the chips can clear back off`() {
        val viewModel = viewModel()

        RELIEF_OPTIONS.forEach { percent ->
            viewModel.setRelief(percent)
            assertEquals(percent, viewModel.uiState.value.reliefPercent)

            viewModel.setRelief(null)
            assertNull(viewModel.uiState.value.reliefPercent)
        }
    }

    @Test
    fun `an existing entry's relief is loaded back into the form`() = runTest {
        coEvery { symptomRepository.getByDate(DATE) } returns SymptomEntry(
            date = DATE,
            severity = Severity.MIGRAINE,
            triggers = emptyList(),
            durationBucket = null,
            reliefPercent = 25,
            medication = null,
            notes = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.reliefPercent)
    }
}
