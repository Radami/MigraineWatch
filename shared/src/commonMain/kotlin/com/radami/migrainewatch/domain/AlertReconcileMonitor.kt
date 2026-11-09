package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** The settings a queued warning depends on; any other setting changing leaves the queue alone. */
private data class WarningSettings(val thresholdHpa: Float, val notificationsEnabled: Boolean)

/**
 * Rebuilds the queued warnings whenever what they are built from changes: a new series landing,
 * or the alert settings changing. Whoever caused the change, on either platform.
 *
 * Watches rather than being called by each cause, since a fetch can start from many places (the
 * hourly worker, a screen opening, a location change) and any one of them could forget. A
 * settings change is rebuilt here too, in [scope], so leaving the settings screen straight away
 * can't cut its reconcile short. Overlaps deliberately with the background refresh, which still
 * reconciles after its own fetch so the queue is current before it reports done; the extra
 * reconcile is cheap, and [AlertReconciler] runs them one at a time.
 */
class AlertReconcileMonitor(
    private val pressureRepository: PressureRepository,
    private val userPreferences: UserPreferences,
    private val alertReconciler: AlertReconciler,
    private val scope: CoroutineScope
) {

    private companion object {
        const val TAG = "AlertReconcile"
    }

    /**
     * Started explicitly at app launch rather than from `init`: created lazily, it would only
     * start watching once something happened to ask for it, missing the changes before that.
     */
    fun start() {
        scope.launch {
            // Merged, so each source is read as it changes even while a reconcile is running:
            // a fetch landing mid-reconcile is queued, not lost to the state flow's conflation.
            merge(seriesLanded(), warningSettingsChanged()).collect {
                val result = alertReconciler.reconcile()
                Logger.d(TAG, "Reconciled after a change: $result")
            }
        }
    }

    /** Only a fetch that stored something; a failed or still-running one changed nothing yet. */
    private fun seriesLanded() = pressureRepository.refreshState.filter { it == RefreshState.Updated }

    /** Changes after the first value; the settings at launch are what the queue was built from. */
    private fun warningSettingsChanged() = userPreferences.settings
        .map { WarningSettings(it.alertThresholdHpa, it.notificationsEnabled) }
        .distinctUntilChanged()
        .drop(1)
}
