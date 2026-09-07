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

    /**
     * Injected here for the sake of starting it: nothing else has a reason to hold it, and a
     * watch that only began once some screen happened to need it would not be watching at the
     * moments that matter.
     */
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

        // The queued warnings describe a series, so they are rebuilt whenever one lands —
        // including the refetch a change of location starts, which nothing else watches for.
        alertReconcileMonitor.start()
    }
}
