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
 * Rebuilds pending warnings whenever a new series lands, whoever triggered the fetch.
 *
 * Watches the repository's refresh state rather than being driven by each caller, since a
 * refresh can start from many places and any one of them could forget to reconcile. Overlaps
 * deliberately with [PressureFetchWorker][com.radami.migrainewatch.workers.PressureFetchWorker],
 * which still reconciles after its own fetch to guarantee the queue is current before it
 * reports done; the extra reconcile is cheap.
 */
@Singleton
class AlertReconcileMonitor @Inject constructor(
    private val pressureRepository: PressureRepository,
    private val alertScheduler: AlertNotificationScheduler,
    @ApplicationScope private val scope: CoroutineScope
) {

    /**
     * Started explicitly from the application rather than an `init` block, since as a singleton
     * it would only run `init` once something happened to inject it.
     */
    fun start() {
        scope.launch {
            pressureRepository.refreshState
                // Only a fetch that actually stored something; a failure or in-flight fetch
                // hasn't changed the series yet.
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
