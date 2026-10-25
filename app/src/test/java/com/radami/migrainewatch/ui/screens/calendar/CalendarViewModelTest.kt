package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.time.toKotlinInstant

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private companion object {
        const val THRESHOLD_HPA = 5f

        /**
         * UTC+14, the only offset in use that puts 10:30 UTC on the next calendar day. A view
         * model counting days in any other zone, the device's included, marks the 10th instead.
         */
        const val KIRITIMATI = "Pacific/Kiritimati"

        /** 10:30 to 11:00 UTC on the 10th: 00:30 to 01:00 on the 11th in [KIRITIMATI]. */
        val DROP_START: Instant = Instant.parse("2026-08-10T10:30:00Z")
        val DROP_END: Instant = Instant.parse("2026-08-10T11:00:00Z")
    }

    private val symptomRepository = mockk<SymptomRepository>()
    private val pressureRepository = mockk<PressureRepository>()
    private val userPreferences = mockk<UserPreferences>()

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        val settings = AppSettings(
            alertThresholdHpa = THRESHOLD_HPA,
            location = LocationData(timezone = KIRITIMATI)
        )
        every { userPreferences.settings } returns flowOf(settings)
        every { symptomRepository.getEntriesInRange(any(), any()) } returns flowOf(emptyList())

        // A 12 hPa drop inside half an hour, well over the threshold.
        val readings = listOf(
            PressureReading(DROP_START.toKotlinInstant(), 1020f, 1020f, DROP_END.toKotlinInstant()),
            PressureReading(DROP_END.toKotlinInstant(), 1008f, 1008f, DROP_END.toKotlinInstant())
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `an event is marked on the day it falls on at the location`() = runTest {
        val viewModel = CalendarViewModel(symptomRepository, pressureRepository, userPreferences)

        // Detection runs on Dispatchers.Default, so wait on the state rather than the scheduler.
        val state = viewModel.uiState.first { it.highRiskDays.isNotEmpty() }

        assertEquals(setOf(LocalDate.of(2026, 8, 11)), state.highRiskDays)
    }
}
