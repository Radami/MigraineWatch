package com.radami.migrainewatch.ui.screens.pressure

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import com.radami.migrainewatch.ui.components.ChartRendering
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PressureViewModelTest {

    private companion object {
        const val HOUR_SECONDS = 3600L
        const val DAY_SECONDS = 24 * HOUR_SECONDS
        const val THRESHOLD_HPA = 5f
    }

    private val pressureRepository = mockk<PressureRepository>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)

    // Real instance rather than a mock: alertsIn is pure, so the test exercises the same
    // detection the Today screen and the notifications go through.
    private val alertUseCase = PressureAlertUseCase(pressureRepository, userPreferences)

    private val testDispatcher = StandardTestDispatcher()

    /** Mutable so tests can drive the empty-chart state; defaults to a completed fetch. */
    private val refreshState = MutableStateFlow(RefreshState.Updated)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = THRESHOLD_HPA))

        // A mock that never emits here would leave the combined flow silent forever.
        every { pressureRepository.refreshState } returns refreshState
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PressureViewModel(pressureRepository, userPreferences, alertUseCase)

    private fun readingsReturn(readings: List<PressureReading>) {
        every { pressureRepository.getReadingsInRange(any(), any()) } returns flowOf(readings)
    }

    /**
     * Detection runs on [Dispatchers.Default], outside the test scheduler's control, so
     * advancing time proves nothing — must wait on the actual state instead.
     */
    private suspend fun PressureViewModel.loadedState(): PressureUiState =
        uiState.first { !it.isLoading }

    @Test
    fun `initial state is loading on the widest range`() = runTest {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(TimeRange.Days7, viewModel.uiState.value.selectedRange)
    }

    @Test
    fun `the current reading is the last one already measured`() = runTest {
        val now = Instant.now()
        readingsReturn(
            listOf(
                PressureReading(now.minusSeconds(HOUR_SECONDS), 1013f, 1013f, now),
                PressureReading(now.plusSeconds(HOUR_SECONDS), 1011f, 1011f, now)
            )
        )

        val state = viewModel().loadedState()

        assertFalse(state.isLoading)
        assertEquals(2, state.readings.size)
        assertEquals(1013f, state.currentPressure)
    }

    @Test
    fun `an upcoming event is listed as an alert`() = runTest {
        val now = Instant.now()
        readingsReturn(
            listOf(
                PressureReading(now, 1020f, 1020f, now),
                PressureReading(now.plusSeconds(12 * HOUR_SECONDS), 1010f, 1010f, now)
            )
        )

        val state = viewModel().loadedState()

        assertEquals(1, state.alertWindows.size)
        assertEquals(PressureDirection.DROP, state.alertWindows[0].direction)
        assertEquals(THRESHOLD_HPA, state.alertThresholdHpa)
    }

    @Test
    fun `an event that finished days ago is not listed`() = runTest {
        val now = Instant.now()
        // Well within range and above threshold, but finished: card lists current events only.
        // Kept clear of the 72h detection edge so it can't pass for the wrong reason.
        readingsReturn(
            listOf(
                PressureReading(now.minusSeconds(48 * HOUR_SECONDS), 1020f, 1020f, now),
                PressureReading(now.minusSeconds(36 * HOUR_SECONDS), 1005f, 1005f, now)
            )
        )

        assertTrue(viewModel().loadedState().alertWindows.isEmpty())
    }

    @Test
    fun `an event past the widest chart range is listed but not shaded`() = runTest {
        val now = Instant.now()
        // Detection reaches 7 days out, the widest chip only 4.5 (a wider chart would squash
        // actionable days), so this event must be listed but marked "not in view".
        readingsReturn(
            listOf(
                PressureReading(now.plusSeconds(6 * DAY_SECONDS), 1020f, 1020f, now),
                PressureReading(now.plusSeconds(6 * DAY_SECONDS + 12 * HOUR_SECONDS), 1008f, 1008f, now)
            )
        )

        val state = viewModel().loadedState()
        val alert = state.alertWindows.single()

        TimeRange.entries.forEach { range ->
            assertFalse(
                "$range should not reach an event six days out",
                ChartWindow.around(now, range.step).covers(alert)
            )
        }
    }

    @Test
    fun `selecting a range changes the chip without touching the data`() = runTest {
        val now = Instant.now()
        readingsReturn(listOf(PressureReading(now, 1013f, 1013f, now)))

        val viewModel = viewModel()
        viewModel.loadedState()

        viewModel.selectRange(TimeRange.Hours24)

        // Applied on tap, not after a DB round trip, so reading state immediately is enough.
        val state = viewModel.uiState.value
        assertEquals(TimeRange.Hours24, state.selectedRange)
        assertEquals(1013f, state.currentPressure)
    }

    /** An empty chart needs to know why it's empty, since an empty table alone can't tell it. */
    @Test
    fun `how the fetch went reaches the screen`() = runTest {
        readingsReturn(emptyList())
        refreshState.value = RefreshState.Failed

        assertEquals(RefreshState.Failed, viewModel().loadedState().refreshState)
    }

    /**
     * A band only makes sense over a full day; shorter steps collapse it onto the line. Pinned
     * here since the chip is the only place that decides this — the chart just draws what it gets.
     */
    @Test
    fun `only the daily step draws a band`() {
        TimeRange.entries.forEach { range ->
            val expected = if (range.step == ChartStep.OneDay) {
                ChartRendering.MinMaxBand
            } else {
                ChartRendering.Line
            }
            assertEquals("$range draws the wrong marks", expected, range.rendering)
        }
    }
}
