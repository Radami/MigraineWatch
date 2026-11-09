package com.radami.migrainewatch.notifications

import androidx.work.WorkManager
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.ui.platform.AlertDebugTools
import com.radami.migrainewatch.workers.PressureFetchWorker
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaZoneId
import javax.inject.Inject

/** The debug actions on Android: WorkManager runs the check, the notifier posts the warning. */
class AndroidAlertDebugTools @Inject constructor(
    private val workManager: WorkManager,
    private val notifier: AlertNotifier
) : AlertDebugTools {

    override fun runAlertCheckNow() {
        PressureFetchWorker.runNow(workManager)
    }

    override suspend fun postNow(alert: AlertWindow, phase: AlertPhase, zone: TimeZone): Boolean =
        notifier.notify(alert, phase, zone.toJavaZoneId())
}
