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
 * What the Today screen shows *while* a new forecast replaces the old one.
 *
 * The screen reads a week it knows nothing about as a failed load, so anything that briefly
 * empties the readings makes it flash "unable to load" over data that arrived perfectly well.
 * A settled-state assertion cannot see that: it samples after everything is over. This one
 * stops the Compose clock and walks the transition frame by frame instead, which is also what
 * keeps the assertions from synchronising past the animations they are meant to inspect.
 *
 * It catches a flash however it is caused — a torn write underneath, or a transition up here
 * that renders the empty branch on its way through. It is a race detector rather than a
 * proof, though, which is why [REFRESHES_ACROSS_WINDOW] is as large as it is, and why
 * PressureRepositoryTest guards the atomic write on its own terms as well.
 */
@HiltAndroidTest
class TodayRefreshTransitionTest {

    private companion object {
        /** The most sensitive level, so the starting scenario reliably marks days at risk. */
        val ALERT_SENSITIVITY = AlertSensitivity.HIGH

        /** Fetches go through OkHttp and Room, and screens animate in, so the UI settles late. */
        const val UI_TIMEOUT_MILLIS = 10_000L

        /**
         * How many frames the transition is watched for, and the real time allowed to pass per
         * frame. The Compose clock is stopped, so stepping it costs no wall time — but the
         * refresh it is watching runs on real threads, and has to be given the chance to land
         * inside the window rather than after it.
         */
        const val OBSERVED_FRAMES = 180
        const val FRAME_MILLIS = 16L

        /**
         * How many refreshes are driven across the window. One is not enough to catch a
         * forecast written in pieces: the gap between clearing the old one and writing the new
         * is short, the screen only renders it if the emission survives long enough to
         * recompose, and this test samples once a frame. Measured against a deliberately
         * un-fixed repository, one refresh caught it in none of three runs and twelve in one
         * of three; forty caught it in four of four, at frames 4 to 6 every time.
         */
        const val REFRESHES_ACROSS_WINDOW = 40

        /**
         * The openings of every message the outlook card falls back to when it has no week to
         * draw. Matched as substrings because one of them carries a timestamp, and asserted
         * together because a flash of any of them is the same defect: a card giving up over
         * data that is arriving. Which one would appear depends on what survived the moment —
         * readings but no usable day, or nothing at all and whatever the fetch was doing.
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

    // MainActivity is launched by the test rather than by the rule, so preferences and the
    // mock scenario are already in place when its ViewModels start collecting.
    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    @Inject lateinit var userPreferences: UserPreferences

    // The same singleton the screen's ViewModel holds. TodayViewModel only refreshes in its
    // init, so a forecast change has to be driven from here to land under a screen that is
    // already up — which is exactly the case the flicker showed up in.
    @Inject lateinit var pressureRepository: PressureRepository

    private var scenario: ActivityScenario<MainActivity>? = null

    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication replaces MigraineWatchApp, which is what normally calls
        // WorkManager.initialize(); without this MainActivity fails on getInstance().
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

        // From here the clock only moves when this test moves it, so assertions read the tree
        // mid-animation instead of waiting for it to settle first.
        composeTestRule.mainClock.autoAdvance = false

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NO_EVENTS
        val refresh = refreshScope.launch {
            repeat(REFRESHES_ACROSS_WINDOW) { pressureRepository.refresh() }
        }

        // The frame the new forecast reached the screen. Recorded rather than assumed: without
        // it a window the change landed *after* would pass this test having watched nothing.
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
     * The other half of the same guarantee: the animations finish. A transition keyed on
     * something unstable re-triggers on every recomposition and never settles, which no
     * assertion taken after `waitForIdle` can see — with animations running, it never returns.
     */
    @Test
    fun theTransitionSettlesOnTheNewForecast() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        awaitText(ELEVATED_HEADLINE)

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NO_EVENTS
        runBlocking { pressureRepository.refresh() }

        awaitText(CLEAR_HEADLINE)

        // Exactly one headline, not the outgoing one left behind next to its replacement:
        // both are in the tree while the crossover runs, and talk-back would read both.
        assertTrue(
            "The outgoing headline was still in the tree after the transition settled",
            nodesWithText(ELEVATED_HEADLINE).isEmpty()
        )
        assertTrue(nodesWithText(CLEAR_HEADLINE).size == 1)
    }
}
