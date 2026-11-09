package com.radami.migrainewatch.ui.screens.onboarding

import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.dto.GeocodingResponse
import com.radami.migrainewatch.data.remote.dto.GeocodingResult
import com.radami.migrainewatch.ui.platform.NotificationPermissions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The city search sends a query only once typing pauses, and only the latest query's answer is
 * shown: replies arrive out of order, and an older, slower one must not replace newer results.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private companion object {
        val ZURICH = GeocodingResult(name = "Zurich", latitude = 47.37, longitude = 8.54, country = "Switzerland")
        val ZUG = GeocodingResult(name = "Zug", latitude = 47.17, longitude = 8.52, country = "Switzerland")

        /** Long enough that the newer query's reply lands well before this one's. */
        const val SLOW_REPLY_MILLIS = 5_000L
    }

    private val geocodingApi = mockk<GeocodingApi>()
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        coEvery { geocodingApi.search("Zurich", any(), any(), any()) } returns GeocodingResponse(listOf(ZURICH))

        viewModel = OnboardingViewModel(geocodingApi, mockk<UserPreferences>(relaxed = true), mockk<NotificationPermissions>())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `fast typing sends only the query typing paused on`() = runTest {
        viewModel.updateSearchQuery("Zu")
        advanceTimeBy(OnboardingViewModel.SEARCH_DEBOUNCE_MILLIS / 2)
        viewModel.updateSearchQuery("Zurich")
        advanceUntilIdle()

        coVerify(exactly = 0) { geocodingApi.search("Zu", any(), any(), any()) }
        assertEquals(listOf(ZURICH), viewModel.uiState.value.searchResults)
    }

    @Test
    fun `a slower reply to an older query does not replace newer results`() = runTest {
        coEvery { geocodingApi.search("Zu", any(), any(), any()) } coAnswers {
            delay(SLOW_REPLY_MILLIS)
            GeocodingResponse(listOf(ZUG))
        }

        // "Zu" has been sent and is still waiting on its reply when "Zurich" is typed.
        viewModel.updateSearchQuery("Zu")
        advanceTimeBy(OnboardingViewModel.SEARCH_DEBOUNCE_MILLIS + 1)
        runCurrent()
        viewModel.updateSearchQuery("Zurich")
        advanceUntilIdle()

        assertEquals(listOf(ZURICH), viewModel.uiState.value.searchResults)
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun `clearing the query back to one letter stops the search`() = runTest {
        viewModel.updateSearchQuery("Zurich")
        viewModel.updateSearchQuery("Z")
        advanceUntilIdle()

        coVerify(exactly = 0) { geocodingApi.search(any(), any(), any(), any()) }
        assertEquals(emptyList<GeocodingResult>(), viewModel.uiState.value.searchResults)
        assertFalse(viewModel.uiState.value.isSearching)
    }
}
