package com.radami.migrainewatch.ui.platform

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import kotlinx.datetime.TimeZone

/** The debug section's actions that need the platform. Never reachable in a release build. */
interface AlertDebugTools {

    /** Runs the production fetch-and-reconcile path now, in the background. */
    fun runAlertCheckNow()

    /**
     * Posts a warning for [alert] straight away, ignoring lead time and notified history, with
     * times in [zone]. Returns whether it reached the system, so a blocked post can say so.
     */
    suspend fun postNow(alert: AlertWindow, phase: AlertPhase, zone: TimeZone): Boolean
}
