package com.radami.migrainewatch

import android.content.Context
import android.content.Intent
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
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Covers two reported bugs: the alert banner disappearing after a round trip, and the bottom
 * bar losing its selection. Also checks that both the banner and the notification land on Pressure.
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

    // Launched by the test, not the rule, so prefs and the mock scenario are set before
    // ViewModels start collecting.
    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    @Inject lateinit var userPreferences: UserPreferences

    private var scenario: ActivityScenario<MainActivity>? = null

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication skips the normal WorkManager.initialize() call, so do it here.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())

        // Fresh install has no location and unfinished onboarding; seed both before launch.
        runBlocking {
            userPreferences.saveLocation(HOME_LOCATION)
            userPreferences.setOnboardingComplete(true)
            userPreferences.setAlertSensitivity(ALERT_SENSITIVITY)
        }

        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.TWO_EVENTS
    }

    /**
     * Starts the app as the launcher would, or — with [openTab] — as the alert notification
     * does, which asks for a tab other than the one the app normally opens on.
     */
    private fun launchApp(openTab: MainActivity.Tab? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            openTab?.let { putExtra(MainActivity.EXTRA_OPEN_TAB, it.name) }
        }
        scenario = ActivityScenario.launch(intent)
    }

    @After
    fun tearDown() {
        scenario?.close()
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS
    }

    /** Existence alone isn't enough: animating nodes can be composed while still off-screen. */
    private fun awaitDisplayed(matcher: SemanticsMatcher) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runCatching { composeTestRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
    }

    /** Uses the unmerged tree: the icon carries the description, the merged node only the label. */
    private fun clickBottomNav(contentDescription: String) {
        composeTestRule.onNodeWithContentDescription(contentDescription, useUnmergedTree = true)
            .performClick()
    }

    @Test
    fun alertBannerSurvivesRoundTripToPressure() {
        launchApp()
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        // The banner leads to the Pressure tab, which lists the event and shades it
        composeTestRule.onNodeWithContentDescription("Pressure alert banner").performClick()
        awaitDisplayed(hasContentDescription("Time range 7 days"))
        composeTestRule.onNode(isSelectable() and hasText("Pressure")).assertIsSelected()

        // Coming back must leave Today exactly as it was, banner included
        Espresso.pressBack()
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(isSelectable() and hasText("Today")).assertIsSelected()
    }

    @Test
    fun notificationOpensPressureWithTodayUnderneath() {
        launchApp(openTab = MainActivity.Tab.PRESSURE)

        awaitDisplayed(hasContentDescription("Time range 7 days"))
        composeTestRule.onNode(isSelectable() and hasText("Pressure")).assertIsSelected()

        // Today is still the destination beneath it, so back is not an exit from the app
        Espresso.pressBack()
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(isSelectable() and hasText("Today")).assertIsSelected()
    }

    @Test
    fun alertBannerAndTabSelectionSurviveBottomBarRoundTrip() {
        launchApp()
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        clickBottomNav("Pressure screen")
        awaitDisplayed(hasContentDescription("Time range 7 days"))

        clickBottomNav("Today screen")

        // Both reported bugs: the banner vanished, and the bar kept the old selection
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(isSelectable() and hasText("Today")).assertIsSelected()
    }
}
