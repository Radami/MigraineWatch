package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.char
import kotlinx.datetime.offsetAt
import kotlin.time.Instant

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

/** "Saturday 14:00" — notification and banner text, where the day is worth spelling out. */
private val WEEKDAY_AND_TIME = DateTimeComponents.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_FULL)
    char(' ')
    hour()
    char(':')
    minute()
}

/**
 * When an event happens, for the notification body and the Today banner's second line, in
 * [zone]: the location's, like every other time the app shows.
 * An event still ahead is described by when it arrives; one already running by when it
 * started, since "starts" would wrongly describe something already past.
 */
fun formatAlertTiming(
    alert: AlertWindow,
    phase: AlertPhase,
    detail: AlertTimingDetail,
    zone: TimeZone
): String {
    val startLabel = alert.start.weekdayAndTimeIn(zone)

    // Only an event already running has an end worth naming: one still ahead is bounded by a
    // start the user can act on, and naming both would bury it.
    val easesSuffix = when (detail) {
        AlertTimingDetail.Brief -> ""
        AlertTimingDetail.WithEnd -> " · eases ${alert.end.weekdayAndTimeIn(zone)}"
    }

    return when (phase) {
        AlertPhase.AHEAD -> "Starts $startLabel"
        AlertPhase.UNDERWAY -> "Underway since $startLabel$easesSuffix"
    }
}

private fun Instant.weekdayAndTimeIn(zone: TimeZone): String =
    format(WEEKDAY_AND_TIME, zone.offsetAt(this))
