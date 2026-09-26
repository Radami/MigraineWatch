package com.radami.migrainewatch

import android.app.Application
import androidx.work.WorkManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.radami.migrainewatch.domain.AlertReconcileMonitor
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MigraineWatchApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    /** Injected only to start it; a watch that began lazily would miss the moments that matter. */
    @Inject lateinit var alertReconcileMonitor: AlertReconcileMonitor
    
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Ensure WorkManager is initialized for Robolectric or cases where 
        // default initializer is disabled
        try {
            WorkManager.initialize(this, workManagerConfiguration)
        } catch (e: IllegalStateException) {
            // Already initialized
        }

        // Rebuilds the queued warnings whenever a new series lands, including a location
        // change's refetch, which nothing else watches for.
        alertReconcileMonitor.start()
    }
}
