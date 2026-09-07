package com.radami.migrainewatch.domain

import android.util.Log
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rebuilds the pending warnings whenever a new series lands, whoever asked for it.
 *
 * The warnings describe a series, so any fetch that replaces one can add, move or remove them.
 * The hourly worker knows that and reconciles after its own fetch, but it is not the only thing
 * that fetches: a change of location refetches everything from inside the repository, and
 * nothing was watching for it. Move from Berlin to Kathmandu and the queued warnings went on
 * describing Berlin's weather — delivered, on the wrong continent — until the worker next
 * happened to fire, up to an hour later.
 *
 * Watched here rather than driven from whatever caused the fetch, for the reason the repository
 * watches for the move itself: a refresh can start in half a dozen places, and each of them
 * remembering to reconcile afterwards is the arrangement that produced the gap. The one thing
 * they have in common is the series they replace, so that is what this listens to.
 *
 * Deliberately overlapping with [PressureFetchWorker][com.radami.migrainewatch.workers.PressureFetchWorker],
 * which still reconciles after its own fetch: a background run must not report itself finished
 * before the queue matches what it fetched, and it can only guarantee that by awaiting the
 * reconcile itself. A repeat costs a recompute that finds nothing to change — reconcile builds
 * the whole pending set every time — which is the cheaper half of that trade.
 */
@Singleton
class AlertReconcileMonitor @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val alertScheduler: AlertNotificationScheduler,
    @ApplicationScope private val scope: CoroutineScope
) {

    /**
     * Started from the application rather than from an `init` block: this is a singleton, so an
     * `init` would only run once something first injected it — and the screens that fetch have
     * no reason to, which would leave the watching to begin at whatever moment the Settings
     * screen was first opened.
     */
    fun start() {
        scope.launch {
            pressureRepository.refreshState
                // Only a fetch that stored something. A failure changed nothing, and an
                // in-flight one has not changed anything yet; reconciling on either would
                // rebuild the queue from the very series it is waiting to replace.
                .filter { it == RefreshState.Updated }
                .collect {
                    val result = alertScheduler.reconcile()
                    Log.d(TAG, "Reconciled after a refresh: $result")
                }
        }
    }

    private companion object {
        const val TAG = "AlertReconcile"
    }
}
