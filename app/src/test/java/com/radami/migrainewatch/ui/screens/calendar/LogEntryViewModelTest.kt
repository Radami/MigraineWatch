package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.domain.Severity
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
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.time.Clock

/**
 * Relief is recorded as chips, not a slider, so 0% and "unanswered" must stay distinguishable.
 * Confusing "did nothing" with "no answer" loses data a user could later disagree with.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogEntryViewModelTest {

    private companion object {
        val DATE: LocalDate = LocalDate(2026, 8, 23)
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

    private fun viewModel() = LogEntryViewModel(DATE, symptomRepository)

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
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now()
        )

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(25, viewModel.uiState.value.reliefPercent)
    }

    @Test
    fun `a failed read of the day's entry opens an empty form`() = runTest {
        coEvery { symptomRepository.getByDate(DATE) } throws IOException("disk unavailable")

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(LogEntryState(date = DATE), viewModel.uiState.value)
    }

    @Test
    fun `a failed save leaves the form ready to save again`() = runTest {
        coEvery { symptomRepository.save(any()) } throws IOException("disk full")
        val viewModel = viewModel()
        viewModel.selectSeverity(Severity.MILD)

        viewModel.save()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSaving)
        assertFalse(viewModel.uiState.value.savedSuccessfully)
    }
}
