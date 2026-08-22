package com.radami.migrainewatch.ui.screens.pressure

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.domain.ChartWindow
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

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        every { userPreferences.settings } returns flowOf(AppSettings(alertThresholdHpa = THRESHOLD_HPA))
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
     * The first state the screen would actually render.
     *
     * Detection runs on [Dispatchers.Default], which the test scheduler has no say over, so
     * advancing it proves nothing: waiting on the state itself is what rules out reading the
     * placeholder the ViewModel starts with.
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
        // Well inside both the query range and the detection history, and far above the
        // threshold, but over and done with: the card lists what is current, not the history
        // the chart happens to reach. Kept clear of the 72 h detection edge on purpose —
        // sitting on it would drop a reading and pass for the wrong reason.
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
        // Six days out: detection reaches seven days ahead, while the widest chip reaches four
        // and a half. That gap is deliberate — a chart wide enough to hold the whole forecast
        // would squash the days the user can still act on — so the row is listed anyway and
        // marked "not in view" rather than being dropped or silently unshaded.
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

        // Applied on the tap rather than after a round trip through the database, so reading
        // the state straight away is enough.
        val state = viewModel.uiState.value
        assertEquals(TimeRange.Hours24, state.selectedRange)
        assertEquals(1013f, state.currentPressure)
    }
}
