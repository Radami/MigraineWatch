package com.radami.migrainewatch.ui

import com.radami.migrainewatch.ui.navigation.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Taps on the app's notifications, waiting for the shared app to act on them. iOS reports a tap
 * whether the app was closed or already open, so a request stays pending until the app shows it
 * and is then cleared, letting the next tap open the same tab again.
 */
class NotificationTaps {

    private val pending = MutableStateFlow<Screen?>(null)

    /** The tab the last unhandled tap asked for, or null once it has been shown. */
    internal val requestedTab: StateFlow<Screen?> = pending.asStateFlow()

    /**
     * Every notification the app posts is a pressure warning. Pressure shades the event on its
     * chart, so a tap opens it there, as a tapped warning does on Android.
     */
    fun warningTapped() {
        pending.value = Screen.Pressure
    }

    /** The requested tab is on screen; the next tap starts a new request. */
    internal fun tabShown() {
        pending.value = null
    }
}
