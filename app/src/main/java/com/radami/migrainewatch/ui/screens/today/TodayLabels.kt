package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.format.formatAlertSummary

/**
 * Every string the Today screen says about a day or a stretch of days.
 *
 * Kept separate so the branching logic can be unit tested without a screen.
 * `internal` so tests can call these directly.
 */

internal fun todayLabel(today: DayOutlook): String = when (today.risk) {
    OutlookRisk.Elevated -> "Elevated risk today"
    OutlookRisk.Clear -> "Clear today"
    OutlookRisk.Unknown -> "No forecast for today"
}

/**
 * What the days after today add up to. Only reports "clear" for days the forecast actually
 * covered; an elevated-day count stays valid even if coverage runs out early. Today itself is
 * excluded since the headline above already covers it.
 */
internal fun weekAheadLabel(outlook: List<DayOutlook>): String {
    val ahead = outlook.drop(1)
    val toWatch = ahead.count { it.risk == OutlookRisk.Elevated }
    if (toWatch > 0) return "$toWatch of the next ${ahead.size} days to watch"

    val covered = ahead.count { it.risk != OutlookRisk.Unknown }
    if (covered == 0) return "No forecast beyond today"
    return "Clear for the next ${dayCount(covered.toLong())}"
}

internal fun outlookDayDescription(day: DayOutlook, isToday: Boolean, weekday: String): String {
    val name = if (isToday) "Today" else "$weekday ${day.date.dayOfMonth}"
    return when (day.risk) {
        OutlookRisk.Elevated ->
            "$name, elevated risk, " +
                formatAlertSummary(day.peakDelta ?: 0f, day.direction ?: PressureDirection.DROP)

        OutlookRisk.Clear -> "$name, clear"
        OutlookRisk.Unknown -> "$name, no forecast"
    }
}

internal fun dayCount(days: Long): String = "$days ${dayUnit(days)}"

internal fun dayUnit(days: Long): String = if (days == 1L) "day" else "days"
