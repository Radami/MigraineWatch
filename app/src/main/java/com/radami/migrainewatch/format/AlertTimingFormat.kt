package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import java.time.ZoneId

/** How much of an event's timing there is room to spell out. */
enum class AlertTimingDetail {

    /**
     * The start, and nothing else. The Today banner, which sits directly above the screen
     * carrying the rest and stays one line for it.
     */
    Brief,

    /**
     * The start and when the event lets up. A notification, which may be read hours after it
     * arrived and has to answer "am I still in this?" on its own.
     */
    WithEnd
}

/**
 * When an event happens, for the notification body and the Today banner's second line.
 * An event still ahead is described by when it arrives; one already running by when it
 * started, since "starts" would wrongly describe something already past.
 */
fun formatAlertTiming(
    alert: AlertWindow,
    phase: AlertPhase,
    detail: AlertTimingDetail,
    zone: ZoneId = ZoneId.systemDefault()
): String {
    val formatter = AppDateFormats.WEEKDAY_AND_TIME
    val startLabel = formatter.format(alert.start.atZone(zone))

    // Only an event already running has an end worth naming: one still ahead is bounded by a
    // start the user can act on, and naming both would bury it.
    val easesSuffix = when (detail) {
        AlertTimingDetail.Brief -> ""
        AlertTimingDetail.WithEnd -> " · eases ${formatter.format(alert.end.atZone(zone))}"
    }

    return when (phase) {
        AlertPhase.AHEAD -> "Starts $startLabel"
        AlertPhase.UNDERWAY -> "Underway since $startLabel$easesSuffix"
    }
}
