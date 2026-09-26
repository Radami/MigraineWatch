package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.PressureDirection
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    /** Mutable so tests can drive the outlook-gap state; defaults to a completed fetch. */
    private val refreshState = MutableStateFlow(RefreshState.Updated)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        
        every { userPreferences.settings } returns flowOf(AppSettings())
        every { symptomRepository.getAllEntries() } returns flowOf(emptyList())

        // A mock that never emits here would leave the combined flow silent forever.
        every { pressureRepository.refreshState } returns refreshState
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Detection runs on [Dispatchers.Default], outside the test scheduler's control, so
     * advancing time proves nothing — must wait on the actual state instead.
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
        // Ended 2h ago, inside the relevance window, so it still marks its day but the
        // banner has nothing left to warn about.
        val end = now.minus(2, ChronoUnit.HOURS)
        val readings = listOf(
            PressureReading(end.minus(24, ChronoUnit.HOURS), 1020f, 1020f, now),
            PressureReading(end, 1010f, 1010f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = 5f))

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        // Waited for, not advanced: an unloaded state also holds an empty list.
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

    @Test
    fun `an event already under way is reported as under way, not as the next one coming`() = runTest {
        val now = Instant.now()
        // Started 6h ago, 6h left: still pending, but "starts" would misdescribe it.
        val readings = listOf(
            PressureReading(now.minus(6, ChronoUnit.HOURS), 1020f, 1020f, now),
            PressureReading(now.plus(6, ChronoUnit.HOURS), 1010f, 1010f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = 5f))

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        val state = viewModel.loadedState()

        assertEquals(1, state.pendingAlerts.size)
        assertEquals(AlertPhase.UNDERWAY, state.leadAlertPhase)
    }

    @Test
    fun `an event still ahead is reported as ahead`() = runTest {
        val now = Instant.now()
        val readings = listOf(
            PressureReading(now.plus(6, ChronoUnit.HOURS), 1020f, 1020f, now),
            PressureReading(now.plus(18, ChronoUnit.HOURS), 1010f, 1010f, now)
        )
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = 5f))

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        assertEquals(AlertPhase.AHEAD, viewModel.loadedState().leadAlertPhase)
    }

    @Test
    fun `no pending event leaves the banner nothing to word`() = runTest {
        val now = Instant.now()
        every { pressureRepository.getReadingsInRange(any(), any()) } returns
            flowOf(listOf(PressureReading(now, 1013f, 1013f, now)))

        assertNull(
            TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
                .loadedState().leadAlertPhase
        )
    }

    @Test
    fun `readings that stop before today are a forecast fallen behind`() = runTest {
        val now = Instant.now()
        // Stale cache, history only. Card dates what it has instead of blaming the connection.
        val readings = (1..12).map { hoursAgo ->
            PressureReading(now.minus(hoursAgo.toLong(), ChronoUnit.HOURS), 1013f, 1013f, now)
        }
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(DayOutlook.DAYS, state.outlook.count { it.risk == OutlookRisk.Unknown })
        assertEquals(OutlookGap.ForecastBehind, state.outlookGap)

        // The card names the moment it last heard anything, so that has to be there.
        assertNotNull(state.lastUpdated)
    }

    @Test
    fun `no readings behind a fetch that found none is a location with no forecast`() = runTest {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(emptyList())
        refreshState.value = RefreshState.NoReadings

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(DayOutlook.DAYS, state.outlook.count { it.risk == OutlookRisk.Unknown })
        assertEquals(OutlookGap.NoReadings, state.outlookGap)

        // Nothing arrived, so there is no moment to date the gap from.
        assertNull(state.lastUpdated)
    }

    /**
     * An empty table looks the same whether the fetch failed, is in flight, or found nothing,
     * so the reason must come from the repository, not the data.
     */
    @Test
    fun `no readings behind a failed fetch is a failed fetch`() = runTest {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(emptyList())
        refreshState.value = RefreshState.Failed

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(OutlookGap.FetchFailed, state.outlookGap)
    }

    /**
     * Room delivers a committed write several executor hops after the fetch returns, so every
     * cold start briefly passes through "succeeded, but readings still empty" — misread as an
     * absent forecast, that flashed a wrong message for a few frames of every launch.
     */
    @Test
    fun `a fetch that has landed but whose readings have not is still loading`() = runTest {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(emptyList())
        refreshState.value = RefreshState.Updated

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(OutlookGap.Loading, state.outlookGap)
    }

    /** Room answers empty immediately, before the fetch even runs; reporting failure here is wrong. */
    @Test
    fun `no readings while a fetch is still out is not a failure`() = runTest {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(emptyList())
        refreshState.value = RefreshState.InFlight

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(OutlookGap.Loading, state.outlookGap)
    }

    /** Nothing to fetch for is not a fetch that went wrong. */
    @Test
    fun `no readings and no location is reported as the missing location`() = runTest {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(emptyList())
        refreshState.value = RefreshState.NoLocation

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(OutlookGap.NoLocation, state.outlookGap)
    }

    /** Stale readings on hand are dated, not diagnosed, whatever the last fetch's own result was. */
    @Test
    fun `readings that fell behind are dated even when the last fetch failed`() = runTest {
        val now = Instant.now()
        val readings = (1..12).map { hoursAgo ->
            PressureReading(now.minus(hoursAgo.toLong(), ChronoUnit.HOURS), 1013f, 1013f, now)
        }
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
        refreshState.value = RefreshState.Failed

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertEquals(OutlookGap.ForecastBehind, state.outlookGap)
        assertNotNull(state.lastUpdated)
    }

    /** Covering today but stopping short of the week is normal, not a failure to report as a gap. */
    @Test
    fun `a forecast covering only today is not a gap`() = runTest {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Through to the last hour of today, and no further.
        val readings = (0 until HOURS_PER_DAY).map { hour ->
            PressureReading(today.atTime(hour, 0).atZone(zone).toInstant(), 1013f, 1013f, now)
        }
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)

        val state = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)
            .loadedState()

        assertNull(state.outlookGap)
        assertEquals(OutlookRisk.Clear, state.outlook.first().risk)
        assertEquals(OutlookRisk.Unknown, state.outlook.last().risk)
    }

    /**
     * Regression: `coverageEnd` and `DayOutlook.forecast` each looked right alone, but together
     * still refused to call the last day clear. Pins the actual series shape the app is handed.
     */
    @Test
    fun `the last outlook day is clear when the forecast ends at 23-00 on it`() = runTest {
        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()

        // Mirrors Open-Meteo's forecast_days=7 shape: last slot is 23:00 on day 7, not midnight
        // on day 8. Flat values, so nothing is detected and every day falls to the coverage check.
        val readings = (0 until DayOutlook.DAYS).flatMap { day ->
            (0 until HOURS_PER_DAY).map { hour ->
                val at = today.plusDays(day.toLong()).atTime(hour, 0).atZone(zone).toInstant()
                PressureReading(at, 1013f, 1013f, now)
            }
        }
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)

        val viewModel = TodayViewModel(pressureRepository, symptomRepository, userPreferences, alertUseCase)

        val state = viewModel.loadedState()
        val outlook = state.outlook

        assertEquals(today.plusDays(DayOutlook.DAYS - 1L), outlook.last().date)
        assertEquals(OutlookRisk.Clear, outlook.last().risk)
        assertTrue(outlook.none { it.risk == OutlookRisk.Unknown })
        assertNull(state.outlookGap)
    }
}
