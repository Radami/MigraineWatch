package com.example.migrainetracker

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.migrainetracker.data.preferences.AlertSensitivity
import com.example.migrainetracker.data.preferences.LocationData
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.remote.mock.MockDataInterceptor
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Covers the two navigation bugs reported against the alert banner: the banner disappearing
 * after a round trip to another screen, and the bottom bar losing its selection.
 */
@HiltAndroidTest
class NavigationTest {

    private companion object {
        /** The most sensitive level, so the TWO_EVENTS scenario always produces a banner. */
        val ALERT_SENSITIVITY = AlertSensitivity.HIGH

        /** Fetches go through OkHttp and Room, and screens animate in, so the UI settles late. */
        const val UI_TIMEOUT_MILLIS = 10_000L

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

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication replaces MigraineTrackerApp, which is what normally calls
        // WorkManager.initialize(); without this MainActivity fails on getInstance().
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())

        // Preferences survive between instrumented runs, so pin every value the test depends on.
        runBlocking {
            userPreferences.saveLocation(HOME_LOCATION)
            userPreferences.setOnboardingComplete(true)
            userPreferences.setAlertSensitivity(ALERT_SENSITIVITY)
        }

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.TWO_EVENTS
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS
    }

    /**
     * Waits for a node to be both present and on screen. Existence alone is not enough:
     * banners and screen transitions animate, so a node can be composed while its bounds
     * are still outside the viewport.
     */
    private fun awaitDisplayed(matcher: SemanticsMatcher) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runCatching { composeTestRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
    }

    /**
     * Bottom bar destinations are found in the unmerged tree: the description sits on the
     * item's icon, and the merged tab node exposes only its label.
     */
    private fun clickBottomNav(contentDescription: String) {
        composeTestRule.onNodeWithContentDescription(contentDescription, useUnmergedTree = true)
            .performClick()
    }

    @Test
    fun alertBannerSurvivesRoundTripToDetail() {
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        // The banner opens the alert in its own activity
        composeTestRule.onNodeWithContentDescription("View details").performClick()
        awaitDisplayed(hasText("Pressure around this event"))

        // Coming back must leave Today exactly as it was, banner included
        Espresso.pressBack()
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(isSelectable() and hasText("Today")).assertIsSelected()
    }

    @Test
    fun alertBannerAndTabSelectionSurviveBottomBarRoundTrip() {
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        clickBottomNav("Pressure screen")
        awaitDisplayed(hasText("Pressure history"))

        clickBottomNav("Today screen")

        // Both reported bugs: the banner vanished, and the bar kept the old selection
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(isSelectable() and hasText("Today")).assertIsSelected()
    }
}
