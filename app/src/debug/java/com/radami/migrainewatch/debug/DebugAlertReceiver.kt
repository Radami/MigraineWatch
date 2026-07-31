package com.radami.migrainewatch.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import com.radami.migrainewatch.workers.PressureFetchWorker

/**
 * Drives the alert pipeline from the command line, so notifications can be exercised without
 * waiting for the hourly worker:
 *
 * ```
 * adb shell am broadcast -n com.radami.migrainewatch/.debug.DebugAlertReceiver \
 *     -a com.radami.migrainewatch.DEBUG_CHECK_ALERTS --es scenario TWO_EVENTS
 * ```
 *
 * The scenario is optional and matches [MockDataInterceptor.Scenario]. Debug builds only —
 * this class does not exist in a release APK.
 */
class DebugAlertReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "DebugAlertReceiver"
        const val EXTRA_SCENARIO = "scenario"
    }

    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_SCENARIO)?.let { name ->
            val scenario = runCatching { MockDataInterceptor.Scenario.valueOf(name.uppercase()) }
                .getOrElse {
                    Log.w(TAG, "Unknown scenario '$name'; keeping ${MockDataInterceptor.currentScenario}")
                    return@let
                }
            MockDataInterceptor.currentScenario = scenario
            Log.i(TAG, "Mock scenario set to $scenario")
        }

        // Fetching through WorkManager reuses the production path end to end: refresh, then
        // reconcile the pending warnings.
        Log.i(TAG, "Running a pressure fetch and alert reconcile")
        PressureFetchWorker.runNow(WorkManager.getInstance(context))
    }
}
