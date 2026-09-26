package com.radami.migrainewatch

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import com.radami.migrainewatch.ui.theme.ALERT_COLOR_COUNT
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34], instrumentedPackages = ["androidx.loader.content"])
class UserJourneyTest {

    private companion object {
        /** Most sensitive setting, so TWO_EVENTS' 9 hPa events qualify regardless of the default. */
        val STARTING_SENSITIVITY = AlertSensitivity.HIGH

        /** Both TWO_EVENTS events are 9 hPa exactly, so the Low level silences the banner. */
        const val SILENT_SENSITIVITY_OPTION = "Alert sensitivity Low"

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

    // Launched per test (see [launchApp]), so each can seed prefs before any ViewModel starts.
    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    @Inject lateinit var userPreferences: UserPreferences

    private val scenarios = mutableListOf<ActivityScenario<*>>()

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication skips the normal WorkManager.initialize() call, so do it here.
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())

        // Start every journey as a returning user, past onboarding and with a location set.
        runBlocking {
            userPreferences.saveLocation(HOME_LOCATION)
            userPreferences.setOnboardingComplete(true)
            userPreferences.setAlertSensitivity(STARTING_SENSITIVITY)

            // Otherwise the picker asks for notification permission and waits on a dialog
            // result Robolectric never delivers.
            userPreferences.setNotificationPermissionRequested(true)
        }
    }

    @After
    fun tearDown() {
        scenarios.reversed().forEach { it.close() }
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS
    }

    private fun launchApp(mockScenario: MockDataInterceptor.Scenario) {
        MockDataInterceptor.currentScenario = mockScenario
        scenarios += ActivityScenario.launch(MainActivity::class.java)

        // The location chip renders only after the first fetch, so it signals Today is loaded.
        awaitDisplayed(hasContentDescription("Location"))
    }

    /** Existence alone isn't enough: animating nodes can be composed while still off-screen. */
    private fun awaitDisplayed(matcher: SemanticsMatcher) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) {
            runCatching { composeTestRule.onNode(matcher).assertIsDisplayed() }.isSuccess
        }
    }

    private fun awaitGone(contentDescription: String) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(contentDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * `performScrollTo` needs the node to already exist, but a LazyColumn never composes what
     * is far below the viewport. Matches ScrollToIndex specifically since the chart also scrolls.
     */
    private fun scrollToInList(matcher: SemanticsMatcher) {
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(matcher)
    }

    /** How many nodes on screen match, without asserting that any do. */
    private fun countOf(matcher: SemanticsMatcher): Int =
        composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().size

    /** Uses the unmerged tree: the icon carries the description, the merged node only the label. */
    private fun clickBottomNav(contentDescription: String) {
        composeTestRule.onNodeWithContentDescription(contentDescription, useUnmergedTree = true)
            .performClick()
    }

    @Test
    fun scenarioA_TheNervousTraveler() {
        launchApp(MockDataInterceptor.Scenario.THREE_EVENTS)

        // 1. Click location chip to change location
        composeTestRule.onNodeWithContentDescription("Location").performClick()

        // 2. The picker opens on its rationale step; skip past the GPS offer
        awaitDisplayed(hasText("Enter city manually"))
        composeTestRule.onNodeWithText("Enter city manually").performClick()

        // 3. Search for Zurich (results come from FakeGeocodingModule)
        awaitDisplayed(hasText("Search city..."))
        composeTestRule.onNodeWithText("Search city...").performTextInput("Zurich")
        awaitDisplayed(hasText("Zurich, Switzerland", substring = true))
        composeTestRule.onNodeWithText("Zurich, Switzerland", substring = true).performClick()

        // 4. Back on Today with Zurich. Uses the chip, not the search field text, as the
        //    signal since the picker stays up on a spinner while the location saves.
        awaitDisplayed(hasContentDescription("Location"))
        awaitDisplayed(hasText("Zurich", substring = true))

        // 5. Scroll first: card position depends on the alert banner's height above it.
        scrollToInList(hasText("7-day outlook"))
        composeTestRule.onNodeWithText("7-day outlook").assertIsDisplayed()
    }

    @Test
    fun scenarioB_TheProactivePatient() {
        // 1. Force a storm scenario so the app starts with a 9 hPa drop in its data
        launchApp(MockDataInterceptor.Scenario.TWO_EVENTS)

        // 2. Matches on description AND text: the outlook headline below also says
        //    "Elevated risk today", so text alone would match two nodes.
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNode(
            hasContentDescription("Pressure alert banner") and
                hasText("Elevated risk", substring = true)
        ).assertIsDisplayed()

        // 3. Tapping the banner switches to the Pressure tab.
        composeTestRule.onNodeWithContentDescription("Pressure alert banner").performClick()

        // 4. Verify the chart and the event that raised the banner
        awaitDisplayed(hasContentDescription("Time range 7 days"))
        scrollToInList(hasText("Pressure drop (", substring = true))
        composeTestRule.onNodeWithText("Pressure drop (", substring = true).assertIsDisplayed()
    }

    @Test
    fun scenarioD_TheCrowdedForecast() {
        // 1. A week holding one more event than the alert palette has colours
        launchApp(MockDataInterceptor.Scenario.FOUR_EVENTS)

        // 2. The Pressure tab is where every current event is listed
        clickBottomNav("Pressure screen")
        awaitDisplayed(hasContentDescription("Time range 7 days"))
        scrollToInList(hasText("Alerts"))

        // 3. Only as many rows as there are palette colours.
        assertEquals(
            "one row per colour in the palette",
            ALERT_COLOR_COUNT,
            countOf(hasText("hPa in 24h", substring = true))
        )

        // 4. Must keep the three soonest, not the three last (order is drop/rise/drop/rise).
        assertEquals("drops shown", 2, countOf(hasContentDescription("pressure drop")))
        assertEquals("rises shown", 1, countOf(hasContentDescription("pressure rise")))

        // 5. Card must disclose the one event it's hiding.
        composeTestRule.onNodeWithText("1 more event not shown").assertIsDisplayed()
    }

    @Test
    fun scenarioE_TheWeekPlanner() {
        // 1. A week with events in it, so the strip has days worth tapping
        launchApp(MockDataInterceptor.Scenario.TWO_EVENTS)

        // 2. Each column merges into one semantics node for screen readers; the comma
        //    disambiguates it from the "Today" bottom-nav tab.
        scrollToInList(hasText("7-day outlook"))
        awaitDisplayed(hasContentDescription("Today,", substring = true))

        // 3. Tapping a day goes to the Pressure tab, like the banner does.
        composeTestRule.onNodeWithContentDescription("Today,", substring = true).performClick()

        // 4. Also confirms the whole column is tappable, not just the number inside it.
        awaitDisplayed(hasContentDescription("Time range 7 days"))
    }

    @Test
    fun scenarioC_TheStoic() {
        // 1. Start with a storm (~10 hPa drop)
        launchApp(MockDataInterceptor.Scenario.TWO_EVENTS)

        // 2. Verify banner is visible on Today screen
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        // 3. Navigate to Settings
        clickBottomNav("Settings screen")

        // 4. Driven via the semantics OnClick action: injected touches work on a real device
        //    but are swallowed under Robolectric without invoking onClick.
        awaitDisplayed(hasContentDescription(SILENT_SENSITIVITY_OPTION))
        composeTestRule.onNodeWithContentDescription(SILENT_SENSITIVITY_OPTION)
            .performSemanticsAction(SemanticsActions.OnClick)

        // 5. Navigate back to Today
        clickBottomNav("Today screen")

        // 6. Verify banner is GONE once the new threshold has propagated through the flow
        awaitGone("Pressure alert banner")
        composeTestRule.onNodeWithContentDescription("Pressure alert banner").assertDoesNotExist()
    }
}
