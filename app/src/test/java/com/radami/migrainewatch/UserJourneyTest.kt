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
        /**
         * Journeys start on the most sensitive setting rather than the default, so the TWO_EVENTS
         * scenario's 9 hPa events both qualify and the tests don't move when the default does.
         */
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

    // Activities are launched per test (see [launchApp]) rather than by the rule, so each
    // test can seed preferences and pick its mock scenario before any ViewModel starts.
    @get:Rule(order = 1)
    val composeTestRule = createEmptyComposeRule()

    @Inject lateinit var userPreferences: UserPreferences

    private val scenarios = mutableListOf<ActivityScenario<*>>()

    @Before
    fun setup() {
        hiltRule.inject()

        // HiltTestApplication replaces MigraineWatchApp, which is what normally calls
        // WorkManager.initialize(); without this MainActivity fails on getInstance().
        WorkManagerTestInitHelper.initializeTestWorkManager(ApplicationProvider.getApplicationContext())

        // Start every journey as a returning user, past onboarding and with a location set.
        runBlocking {
            userPreferences.saveLocation(HOME_LOCATION)
            userPreferences.setOnboardingComplete(true)
            userPreferences.setAlertSensitivity(STARTING_SENSITIVITY)

            // A returning user has already met the notification prompt. Without this the
            // location picker finishes by asking for the permission and waits on a dialog
            // result Robolectric never delivers, leaving it stuck on its spinner.
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

        // The location chip only renders once the first pressure fetch has landed, which
        // makes it the signal that Today is done loading rather than showing placeholders.
        awaitDisplayed(hasContentDescription("Location"))
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

    private fun awaitGone(contentDescription: String) {
        composeTestRule.waitUntil(UI_TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithContentDescription(contentDescription)
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }

    /**
     * Scrolls the screen's lazy list until [matcher] resolves.
     *
     * `performScrollTo` cannot do this: it needs the node to exist already, and a LazyColumn
     * never composes what sits far below the viewport. A card pushed down by a tall alert
     * banner is therefore not merely off screen, it is absent from the tree entirely.
     *
     * Matched on ScrollToIndex rather than on any scroll action, because the pressure chart
     * scrolls too and only a lazy list can be scrolled by index.
     */
    private fun scrollToInList(matcher: SemanticsMatcher) {
        composeTestRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex))
            .performScrollToNode(matcher)
    }

    /** How many nodes on screen match, without asserting that any do. */
    private fun countOf(matcher: SemanticsMatcher): Int =
        composeTestRule.onAllNodes(matcher).fetchSemanticsNodes().size

    /**
     * Bottom bar destinations are found in the unmerged tree: the description sits on the
     * item's icon, and the merged tab node exposes only its label.
     */
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

        // 4. Verify we are back on Today screen with Zurich. The chip has to be the signal:
        //    the picker stays up on a spinner while the location saves, and "Zurich" alone
        //    matches the search field this test just typed into.
        awaitDisplayed(hasContentDescription("Location"))
        awaitDisplayed(hasText("Zurich", substring = true))

        // 5. Verify the forecast is present. Whether the card starts on screen depends on
        //    how tall the alert banner above it ends up, so scroll it into view first.
        scrollToInList(hasText("7-day outlook"))
        composeTestRule.onNodeWithText("7-day outlook").assertIsDisplayed()
    }

    @Test
    fun scenarioB_TheProactivePatient() {
        // 1. Force a storm scenario so the app starts with a 9 hPa drop in its data
        launchApp(MockDataInterceptor.Scenario.TWO_EVENTS)

        // 2. Verify "Elevated risk" banner is displayed
        awaitDisplayed(hasContentDescription("Pressure alert banner"))
        composeTestRule.onNodeWithText("Elevated risk", substring = true).assertIsDisplayed()

        // 3. Tapping through switches to the Pressure tab, which is where every current
        //    event is listed and shaded on the chart
        composeTestRule.onNodeWithContentDescription("View details").performClick()

        // 4. Verify the chart and the event that raised the banner
        awaitDisplayed(hasContentDescription("Time range 7 days"))
        scrollToInList(hasText("hPa pressure drop", substring = true))
        composeTestRule.onNodeWithText("hPa pressure drop", substring = true).assertIsDisplayed()
    }

    @Test
    fun scenarioD_TheCrowdedForecast() {
        // 1. A week holding one more event than the alert palette has colours
        launchApp(MockDataInterceptor.Scenario.FOUR_EVENTS)

        // 2. The Pressure tab is where every current event is listed
        clickBottomNav("Pressure screen")
        awaitDisplayed(hasContentDescription("Time range 7 days"))
        scrollToInList(hasText("Alerts"))

        // 3. Only as many rows as there are colours to tell one event's shading from another's
        assertEquals(
            "one row per colour in the palette",
            ALERT_COLOR_COUNT,
            countOf(hasText("hPa pressure", substring = true))
        )

        // 4. The three kept are the three soonest, not the three last: the scenario runs
        //    drop, rise, drop, rise, so keeping the wrong end would invert these counts.
        assertEquals("drops shown", 2, countOf(hasContentDescription("pressure drop")))
        assertEquals("rises shown", 1, countOf(hasContentDescription("pressure rise")))

        // 5. And the card says what it is holding back, so three never passes for all of them
        composeTestRule.onNodeWithText("1 more event not shown").assertIsDisplayed()
    }

    @Test
    fun scenarioC_TheStoic() {
        // 1. Start with a storm (~10 hPa drop)
        launchApp(MockDataInterceptor.Scenario.TWO_EVENTS)

        // 2. Verify banner is visible on Today screen
        awaitDisplayed(hasContentDescription("Pressure alert banner"))

        // 3. Navigate to Settings
        clickBottomNav("Settings screen")

        // 4. Drop to the least sensitive level, above every event in the data. The segmented
        //    control is driven through its semantics action: injected touches reach it on a
        //    real device (NavigationTest) but not under Robolectric, where they are swallowed
        //    without invoking onClick.
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
