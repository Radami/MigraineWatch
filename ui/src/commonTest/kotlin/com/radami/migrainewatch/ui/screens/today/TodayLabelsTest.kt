package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Today screen copy has many branches over risk, coverage, and plurals. A wrong day count or
 * "quiet" label for unknown days is wrong in a way no screenshot catches.
 */
class TodayLabelsTest {

    private val today = LocalDate(2026, 8, 23)

    private fun day(
        offset: Long,
        risk: OutlookRisk,
        peakDelta: Float? = null,
        direction: PressureDirection? = null
    ) = DayOutlook(today.plus(offset, DateTimeUnit.DAY), risk, peakDelta, direction)

    /** A full week, today first, with [risks] read off in order. */
    private fun outlook(vararg risks: OutlookRisk) =
        risks.mapIndexed { offset, risk -> day(offset.toLong(), risk) }

    private fun weekOf(todayRisk: OutlookRisk, aheadRisk: OutlookRisk) =
        outlook(todayRisk, *Array(DayOutlook.DAYS - 1) { aheadRisk })

    @Test
    fun todayIsNamedByItsOwnRisk() {
        assertEquals(
            "Elevated risk today",
            todayLabel(day(0, OutlookRisk.Elevated, 9f, PressureDirection.DROP))
        )
        assertEquals("Clear today", todayLabel(day(0, OutlookRisk.Clear)))
        assertEquals("No forecast for today", todayLabel(day(0, OutlookRisk.Unknown)))
    }

    @Test
    fun aClearWeekCountsEveryDayAfterToday() {
        // Pins the label's own arithmetic (6 days follow today); coverage timing is
        // TodayViewModelTest's concern.
        assertEquals(
            "Clear for the next 6 days",
            weekAheadLabel(weekOf(OutlookRisk.Clear, OutlookRisk.Clear))
        )
    }

    @Test
    fun onlyTheDaysTheForecastReachedAreCalledClear() {
        val outlook = outlook(
            OutlookRisk.Clear,
            OutlookRisk.Clear,
            OutlookRisk.Clear,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown
        )

        assertEquals("Clear for the next 2 days", weekAheadLabel(outlook))
    }

    @Test
    fun oneCoveredDayAheadIsSingular() {
        val outlook = outlook(
            OutlookRisk.Clear,
            OutlookRisk.Clear,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown,
            OutlookRisk.Unknown
        )

        assertEquals("Clear for the next 1 day", weekAheadLabel(outlook))
    }

    @Test
    fun aForecastStoppingAtTodaySaysSoRatherThanReportingQuietDays() {
        assertEquals(
            "No forecast beyond today",
            weekAheadLabel(weekOf(OutlookRisk.Clear, OutlookRisk.Unknown))
        )
    }

    @Test
    fun daysToWatchAreCountedAgainstTheWholeWeekAhead() {
        val outlook = outlook(
            OutlookRisk.Clear,
            OutlookRisk.Elevated,
            OutlookRisk.Elevated,
            OutlookRisk.Clear,
            OutlookRisk.Clear,
            OutlookRisk.Clear,
            OutlookRisk.Clear
        )

        assertEquals("2 of the next 6 days to watch", weekAheadLabel(outlook))
    }

    @Test
    fun todayIsNotCountedAmongTheDaysAhead() {
        // Today elevated and the rest quiet: the headline speaks for today, so the line below
        // it has nothing to report. Counting today here would say "1 of the next 6".
        assertEquals(
            "Clear for the next 6 days",
            weekAheadLabel(weekOf(OutlookRisk.Elevated, OutlookRisk.Clear))
        )
    }

    @Test
    fun anElevatedDayIsDescribedByItsLargestEvent() {
        val description = outlookDayDescription(
            day(1, OutlookRisk.Elevated, 8.25f, PressureDirection.RISE),
            isToday = false,
            weekday = "Mon"
        )

        assertEquals("Mon 24, elevated risk, pressure rise (8.3 hPa in 24h)", description)
    }

    @Test
    fun theFirstColumnIsAnnouncedAsTodayRatherThanByItsWeekday() {
        val description = outlookDayDescription(
            day(0, OutlookRisk.Clear),
            isToday = true,
            weekday = "Sun"
        )

        assertEquals("Today, clear", description)
        assertTrue(!description.contains("Sun"))
    }

    @Test
    fun aDayTheForecastNeverReachedIsAnnouncedAsUnknownNotAsQuiet() {
        assertEquals(
            "Sat 29, no forecast",
            outlookDayDescription(day(6, OutlookRisk.Unknown), isToday = false, weekday = "Sat")
        )
    }

    @Test
    fun aSingleDayReadsSingularAndTheRestPlural() {
        assertEquals("1 day", dayCount(1))
        assertEquals("0 days", dayCount(0))
        assertEquals("2 days", dayCount(2))
    }
}
