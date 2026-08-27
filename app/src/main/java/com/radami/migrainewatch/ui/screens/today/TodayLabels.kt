package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import com.radami.migrainewatch.format.formatAlertSummary

/**
 * Every string the Today screen says about a day or a stretch of days.
 *
 * Separated from the composables that show them because they are the part worth testing: each
 * one is a small pile of branches over risk, coverage and plurals, and none of it needs a
 * screen to check. `internal` rather than private for the same reason — the tests read them
 * directly instead of going through a rendered card.
 */

internal fun todayLabel(today: DayOutlook): String = when (today.risk) {
    OutlookRisk.Elevated -> "Elevated risk today"
    OutlookRisk.Clear -> "Clear today"
    OutlookRisk.Unknown -> "No forecast for today"
}

/**
 * What the days after today add up to.
 *
 * Only the days the forecast actually reached can be called clear, so a short forecast says
 * how far it got rather than reporting quiet days it knows nothing about. The count of days to
 * watch is deliberately not hedged the same way: a detected event is known whatever the
 * forecast does after it, and a day the readings never reached cannot add to the count anyway,
 * so "2 of the next 6 days" stays true where coverage runs out early.
 *
 * Today is dropped rather than counted, so the days counted here and the days circled in the
 * strip are deliberately different sets: the headline above already speaks for today, and
 * counting it twice would have the card say the same thing in two voices.
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
