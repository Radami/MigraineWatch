package com.radami.migrainewatch.ui.platform

import com.radami.migrainewatch.IosAppGraph
import com.radami.migrainewatch.RefreshPolicy
import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.format.AlertTimingDetail
import com.radami.migrainewatch.format.formatAlertHeadline
import com.radami.migrainewatch.format.formatAlertTiming
import com.radami.migrainewatch.notifications.IosNotificationCenter
import com.radami.migrainewatch.notifications.LocalNotification
import com.radami.migrainewatch.notifications.LocalNotificationCenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * Outside the alert queue's own names, so a reconcile never mistakes the preview for a warning
 * it scheduled and cancels it.
 */
private const val PREVIEW_IDENTIFIER = "debug-preview"

/** The debug actions on iOS: the app graph runs the check, the notification center posts. */
class IosAlertDebugTools(
    private val graph: IosAppGraph,
    private val scope: CoroutineScope,
    private val center: LocalNotificationCenter = IosNotificationCenter()
) : AlertDebugTools {

    /** The outcome reaches the screen through the reconciler's results; a failed fetch is logged there. */
    override fun runAlertCheckNow() {
        scope.launch { graph.refreshAndReconcile(RefreshPolicy.ALWAYS) }
    }

    /** Worded as the real warning is, so the preview shows what the user would get. */
    override suspend fun postNow(alert: AlertWindow, phase: AlertPhase, zone: TimeZone): Boolean =
        center.schedule(
            LocalNotification(
                identifier = PREVIEW_IDENTIFIER,
                title = formatAlertHeadline(alert.delta, alert.direction),
                body = formatAlertTiming(alert, phase, AlertTimingDetail.WithEnd, zone),
                fireAt = Clock.System.now()
            )
        )
}
