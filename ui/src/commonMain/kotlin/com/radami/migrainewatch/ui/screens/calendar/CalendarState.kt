package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.model.toPressureSample
import com.radami.migrainewatch.domain.AlertDetector
import com.radami.migrainewatch.domain.Severity
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.minusMonth
import kotlinx.datetime.plus
import kotlinx.datetime.plusMonth
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.yearMonth
import kotlin.time.Instant

/** How far back the statistics card counts, ending today. */
private const val STATS_MONTHS = 12

/**
 * The month the calendar shows, and what has to be queried to show it. Everything is counted at
 * the location: which month is current, and where its days start and end.
 */
data class CalendarPeriod(
    val month: YearMonth,
    /** Today at the location, which the grid marks and the statistics count back from. */
    val today: LocalDate,
    val zone: TimeZone,
    /** Where the shown month's first day starts, for its pressure readings. */
    val readingsFrom: Instant,
    /** Where the day after the shown month starts. */
    val readingsUntil: Instant,
    /** The first day of the rolling year the statistics count. */
    val statsFrom: LocalDate
)

/**
 * The period for a month [monthOffset] months back (negative) or ahead of the location's
 * current one. An offset rather than a month, since which month is current depends on the zone.
 */
fun calendarPeriod(monthOffset: Long, zone: TimeZone, now: Instant): CalendarPeriod {
    val today = now.toLocalDateTime(zone).date
    val month = today.yearMonth.plus(monthOffset, DateTimeUnit.MONTH)

    return CalendarPeriod(
        month = month,
        today = today,
        zone = zone,
        readingsFrom = month.firstDay.atStartOfDayIn(zone),
        readingsUntil = month.plusMonth().firstDay.atStartOfDayIn(zone),
        statsFrom = today.minus(STATS_MONTHS, DateTimeUnit.MONTH)
    )
}

/** What the Calendar screen shows for one [CalendarPeriod]. */
data class CalendarState(
    val month: YearMonth,
    val today: LocalDate,
    val entriesInMonth: Map<LocalDate, SymptomEntry>,
    // Days touched by a pressure event that crossed the user's alert threshold. The calendar
    // marks these as high risk; which way the pressure moved is not shown.
    val highRiskDays: Set<LocalDate>,
    // Entry counts per severity for the statistics card. All periods are relative to
    // today, not to the month being browsed in the calendar.
    val monthLogCounts: Map<Severity, Int>,
    val lastMonthLogCounts: Map<Severity, Int>,
    val last12MonthsLogCounts: Map<Severity, Int>
)

/**
 * Builds the Calendar screen from what was queried for [period]. Pure, so every rule can be
 * tested without a device or a database. Detects events across the whole month, so callers on
 * a UI thread should run it in the background.
 *
 * @param entries the shown month's entries.
 * @param readings the shown month's pressure readings.
 * @param statsEntries every entry from [CalendarPeriod.statsFrom] through today.
 */
fun calendarState(
    period: CalendarPeriod,
    entries: List<SymptomEntry>,
    readings: List<PressureReading>,
    statsEntries: List<SymptomEntry>,
    thresholdHpa: Float
): CalendarState {
    val alerts = AlertDetector.detect(readings.map { it.toPressureSample() }, thresholdHpa)

    // The statistics count back from today's month, whichever month is being browsed.
    val thisMonth = period.today.yearMonth

    return CalendarState(
        month = period.month,
        today = period.today,
        entriesInMonth = entries.associateBy { it.date },
        highRiskDays = AlertDetector.eventDays(alerts, period.zone),
        monthLogCounts = statsEntries.severityCountsIn(thisMonth),
        lastMonthLogCounts = statsEntries.severityCountsIn(thisMonth.minusMonth()),
        // The queried range is exactly the rolling year.
        last12MonthsLogCounts = statsEntries.severityCounts()
    )
}

private fun List<SymptomEntry>.severityCounts(): Map<Severity, Int> =
    groupingBy { it.severity }.eachCount()

private fun List<SymptomEntry>.severityCountsIn(month: YearMonth): Map<Severity, Int> =
    filter { it.date.yearMonth == month }.severityCounts()
