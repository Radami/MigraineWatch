package com.radami.migrainewatch.ui.screens.today

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.radami.migrainewatch.MainActivity
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import com.radami.migrainewatch.data.repository.PressureRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * A briefly empty readings list reads as a failed load, so a fast forecast swap can flash
 * "unable to load" over data that arrived fine. Stops the Compose clock and walks the
 * transition frame by frame; a settled-state assertion samples too late to see this.
 */
@HiltAndroidTest
class TodayRefreshTransitionTest {

    private companion object {
        /** The most sensitive level, so the starting scenario reliably marks days at risk. */
        val ALERT_SENSITIVITY = AlertSensitivity.HIGH

        /** Fetches go through OkHttp and Room, and screens animate in, so the UI settles late. */
        const val UI_TIMEOUT_MILLIS = 10_000L

        /** The Compose clock is stopped, but the refresh runs on real threads and needs real time. */
        const val OBSERVED_FRAMES = 180
        const val FRAME_MILLIS = 16L

        /**
         * One refresh rarely catches a torn write since the gap is short and sampling is once
         * per frame. Against a deliberately broken repository, 40 caught it 4/4 runs; 1 caught it 0/3.
         */
        const val REFRESHES_ACROSS_WINDOW = 40

        /**
         * Fallback messages the outlook card shows with no week to draw. Matched as substrings
         * since one carries a timestamp; any of them appearing mid-transition is the same defect.
         */
        val PLACEHOLDER_OPENINGS = listOf(
            "Forecast is out of date",
            "Couldn't reach the forecast",
            "No forecast available",
            "Loading forecast"
        )

        /** The headline before the refresh, and after it. */
        const val ELEVATED_HEADLINE = "Elevated risk today"
        const val CLEAR_HEADLINE = "Clear today"

        val HOME_LOCATION = LocationData(
            source = "manual",
            lat = 52.52,
            lon = 13.41,
            name = "Berlin, Germany",
            timezone = "Europe/Berlin"
        )
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Launched by the test, not the rule, so prefs and the mock scenario are set before
    // ViewModels start collecting.
    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    @Inject lateinit var userPreferences: UserPreferences

    // Same singleton the ViewModel holds. TodayViewModel only refreshes in init, so a forecast
    // change must be driven from here, against a screen already up — the flicker's actual case.
    @Inject lateinit var pressureRepository: PressureRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication skips the normal WorkManager.initialize() call, so do it here.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        runBlocking {
            userPreferences.saveLocation(HOME_LOCATION)
            userPreferences.setOnboardingComplete(true)
            userPreferences.setAlertSensitivity(ALERT_SENSITIVITY)
        }

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.TWO_EVENTS
    }

    @After
    fun tearDown() {
        refreshScope.cancel()
        scenario?.close()
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS
    }

    private fun nodesWithText(text: String) =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes()

    /** The placeholder currently on screen, or null when the card is showing a week. */
    private fun visiblePlaceholder(): String? = PLACEHOLDER_OPENINGS.firstOrNull { opening ->
        composeTestRule.onAllNodesWithText(opening, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitText(text: String) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) { nodesWithText(text).isNotEmpty() }
    }

    @Test
    fun replacingTheForecastNeverFlashesAFailedLoad() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText(ELEVATED_HEADLINE)

        // Clock only moves when told, so assertions read the tree mid-animation.
        composeTestRule.mainClock.autoAdvance = false

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NO_EVENTS
        val refresh = refreshScope.launch {
            repeat(REFRESHES_ACROSS_WINDOW) { pressureRepository.refresh() }
        }

        // Recorded, not assumed: otherwise a change landing after the window still passes.
        var settledAtFrame = -1

        repeat(OBSERVED_FRAMES) { frame ->
            val placeholder = visiblePlaceholder()
            assertNull(
                "Outlook card fell back to a placeholder $frame frames into the transition",
                placeholder
            )
            if (settledAtFrame < 0 && nodesWithText(CLEAR_HEADLINE).isNotEmpty()) {
                settledAtFrame = frame
            }

            composeTestRule.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME_MILLIS)
        }

        runBlocking { refresh.join() }

        assertTrue(
            "The new forecast never landed inside the observed window, so nothing was watched",
            settledAtFrame >= 0
        )
    }

    /**
     * Checks the animation actually settles. A transition keyed on something unstable would
     * re-trigger forever, which `waitForIdle` can't detect since it never returns.
     */
    @Test
    fun theTransitionSettlesOnTheNewForecast() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText(ELEVATED_HEADLINE)

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NO_EVENTS
        runBlocking { pressureRepository.refresh() }

        awaitText(CLEAR_HEADLINE)

        // Must be exactly one headline; both are in the tree mid-crossover and talk-back would read both.
        assertTrue(
            "The outgoing headline was still in the tree after the transition settled",
            nodesWithText(ELEVATED_HEADLINE).isEmpty()
        )
        assertTrue(nodesWithText(CLEAR_HEADLINE).size == 1)
    }
}
