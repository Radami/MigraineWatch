package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.PressureDirection
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    private companion object {
        /** Readings per day in the hourly series Open-Meteo returns. */
        const val HOURS_PER_DAY = 24
    }

    private val pressureRepository = mockk<PressureRepository>(relaxed = true)
    private val symptomRepository = mockk<SymptomRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    
    // Real instance rather than a mock: alertsIn is pure, so this keeps the test exercising
    // the same detection path the app uses.
    private val alertUseCase = PressureAlertUseCase(pressureRepository, userPreferences)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        every { userPreferences.settings } returns flowOf(AppSettings())
        every { symptomRepository.getAllEntries() } returns flowOf(emptyList())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * The first state the screen would actually render.
     *
     * Detection runs on [Dispatchers.Default], which the test scheduler has no say over, so
     * advancing it proves nothing: waiting on the state itself is what rules out reading the
     * placeholder the ViewModel starts with. Same helper as PressureViewModelTest, same reason.
     */
    private suspend fun TodayViewModel.loadedState(): TodayUiState =
        uiState.first { !it.isLoading }

    @Test
    fun `initial state is loading`() = runTest {
        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
        assertEquals(true, viewModel.uiState.value.isLoading)
    }

    @Test
    fun `uiState updates when data is loaded`() = runTest {
        val now = Instant.now()
        val readings = listOf(
            PressureReading(now, 1013f, 1013f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        
        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        val state = viewModel.loadedState()

        assertFalse(state.isLoading)
        assertEquals(DayOutlook.DAYS, state.outlook.size)
        assertEquals(LocalDate.now(), state.outlook.first().date)
    }

    @Test
    fun `an event that has already finished is not offered to the banner`() = runTest {
        val now = Instant.now()
        // A 10 hPa drop that ended two hours ago: inside the relevance window, so it still
        // counts as current and still marks its day — but the banner warns, and there is
        // nothing left to warn about.
        val end = now.minus(2, ChronoUnit.HOURS)
        val readings = listOf(
            PressureReading(end.minus(24, ChronoUnit.HOURS), 1020f, 1020f, now),
            PressureReading(end, 1010f, 1010f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = 5f))

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        // Waited for rather than advanced: an empty list is also what an unloaded state holds,
        // so advancing the scheduler would let this pass without the screen ever loading.
        assertTrue(viewModel.loadedState().pendingAlerts.isEmpty())
    }

    @Test
    fun `pendingAlerts populated when alert detected`() = runTest {
        val now = Instant.now()
        // Create a 10hPa drop over 12 hours
        val readings = listOf(
            PressureReading(now, 1020f, 1020f, now),
            PressureReading(now.plusSeconds(12 * 3600), 1010f, 1010f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = 5f))
        
        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        val state = viewModel.loadedState()

        assertEquals(1, state.pendingAlerts.size)
        assertEquals(PressureDirection.DROP, state.pendingAlerts[0].direction)

        // The event the banner reports is the same one the outlook marks.
        val toWatch = state.outlook.filter { it.risk == OutlookRisk.Elevated }
        assertTrue(toWatch.isNotEmpty())
        assertEquals(10f, toWatch.first().peakDelta!!, 0.01f)
        assertEquals(PressureDirection.DROP, toWatch.first().direction)
    }

    /**
     * The composition, not either half of it: `coverageEnd` and `DayOutlook.forecast` each
     * looked right on their own while the screen still refused to call its own last day clear.
     * What pins it is the series shape the app is actually handed.
     */
    @Test
    fun `the last outlook day is clear when the forecast ends at 23-00 on it`() = runTest {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // What Open-Meteo returns for forecast_days=7: whole days of hourly readings, so the
        // last slot is 23:00 on the seventh day rather than midnight on the eighth. Built from
        // wall-clock times, the way the response is parsed, so a DST week keeps this shape.
        // Flat, so nothing is detected and every day falls through to the coverage check.
        val readings = (0 until DayOutlook.DAYS).flatMap { day ->
            (0 until HOURS_PER_DAY).map { hour ->
                val at = today.plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant()
                PressureReading(at, 1013f, 1013f, now)
            }
        }
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        val outlook = viewModel.loadedState().outlook

        assertEquals(today.plusDays(DayOutlook.DAYS - 1L), outlook.last().date)
        assertEquals(OutlookRisk.Clear, outlook.last().risk)
        assertTrue(outlook.none { it.risk == OutlookRisk.Unknown })
    }
}
