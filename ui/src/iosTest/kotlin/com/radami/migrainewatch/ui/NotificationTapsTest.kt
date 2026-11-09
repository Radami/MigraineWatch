package com.radami.migrainewatch.ui

import com.radami.migrainewatch.ui.navigation.Screen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * How a tapped warning reaches the shared app. A state flow ignores a value equal to the one it
 * holds, so a request that were never cleared would leave every later tap doing nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationTapsTest {

    @Test
    fun nothingIsRequestedBeforeATap() {
        assertNull(NotificationTaps().requestedTab.value)
    }

    @Test
    fun aTappedWarningAsksForPressure() {
        val taps = NotificationTaps()

        taps.warningTapped()

        assertEquals(Screen.Pressure, taps.requestedTab.value)
    }

    @Test
    fun showingTheTabClearsTheRequest() {
        val taps = NotificationTaps()
        taps.warningTapped()

        taps.tabShown()

        assertNull(taps.requestedTab.value)
    }

    /** Reads what the app is sent, not the latest value, which is Pressure whether or not it cleared. */
    @Test
    fun aLaterTapReachesTheAppAsANewRequest() = runTest {
        val taps = NotificationTaps()
        val sent = mutableListOf<Screen?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { taps.requestedTab.toList(sent) }

        taps.warningTapped()
        taps.tabShown()
        taps.warningTapped()

        assertEquals(listOf<Screen?>(null, Screen.Pressure, null, Screen.Pressure), sent)
    }
}
