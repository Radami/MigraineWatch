package com.radami.migrainewatch.ui.screens.today

import com.radami.migrainewatch.domain.DayOutlook
import com.radami.migrainewatch.domain.OutlookRisk
import com.radami.migrainewatch.domain.PressureDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Today screen's copy. Worth its own tests because every one of these is a pile of
 * branches over risk, coverage and plurals, and a card that reports six clear days as five —
 * or calls a day quiet that nothing is known about — is wrong in a way no screenshot catches.
 */
class TodayLabelsTest {

    private val today = LocalDate.of(2026, 8, 23)

    private fun day(
        offset: Long,
        risk: OutlookRisk,
        peakDelta: Float? = null,
        direction: PressureDirection? = null
    ) = DayOutlook(today.plusDays(offset), risk, peakDelta, direction)

    /** A full week, today first, with [risks] read off in order. */
    private fun outlook(vararg risks: OutlookRisk) =
        risks.mapIndexed { offset, risk -> day(offset.toLong(), risk) }

    private fun weekOf(todayRisk: OutlookRisk, aheadRisk: OutlookRisk) =
        outlook(todayRisk, *Array(DayOutlook.DAYS - 1) { aheadRisk })

    @Test
    fun `today is named by its own risk`() {
        assertEquals(
            "Elevated risk today",
            todayLabel(day(0, OutlookRisk.Elevated, 9f, PressureDirection.DROP))
        )
        assertEquals("Clear today", todayLabel(day(0, OutlookRisk.Clear)))
        assertEquals("No forecast for today", todayLabel(day(0, OutlookRisk.Unknown)))
    }

    @Test
    fun `a clear week counts every day after today`() {
        // Six days follow today, so six is what a fully covered week reports. This pins the
        // label's own arithmetic; that the days arrive Clear rather than Unknown in the first
        // place is TodayViewModelTest's 23:00 case.
        assertEquals(
            "Clear for the next 6 days",
            weekAheadLabel(weekOf(OutlookRisk.Clear, OutlookRisk.Clear))
        )
    }

    @Test
    fun `only the days the forecast reached are called clear`() {
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
    fun `one covered day ahead is singular`() {
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
    fun `a forecast stopping at today says so rather than reporting quiet days`() {
        assertEquals(
            "No forecast beyond today",
            weekAheadLabel(weekOf(OutlookRisk.Clear, OutlookRisk.Unknown))
        )
    }

    @Test
    fun `days to watch are counted against the whole week ahead`() {
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
    fun `today is not counted among the days ahead`() {
        // Today elevated and the rest quiet: the headline speaks for today, so the line below
        // it has nothing to report. Counting today here would say "1 of the next 6".
        assertEquals(
            "Clear for the next 6 days",
            weekAheadLabel(weekOf(OutlookRisk.Elevated, OutlookRisk.Clear))
        )
    }

    @Test
    fun `an elevated day is described by its largest event`() {
        val description = outlookDayDescription(
            day(1, OutlookRisk.Elevated, 8.25f, PressureDirection.RISE),
            isToday = false,
            weekday = "Mon"
        )

        assertEquals("Mon 24, elevated risk, pressure rise (8.3 hPa in 24h)", description)
    }

    @Test
    fun `the first column is announced as today rather than by its weekday`() {
        val description = outlookDayDescription(
            day(0, OutlookRisk.Clear),
            isToday = true,
            weekday = "Sun"
        )

        assertEquals("Today, clear", description)
        assertTrue(!description.contains("Sun"))
    }

    @Test
    fun `a day the forecast never reached is announced as unknown, not as quiet`() {
        assertEquals(
            "Sat 29, no forecast",
            outlookDayDescription(day(6, OutlookRisk.Unknown), isToday = false, weekday = "Sat")
        )
    }

    @Test
    fun `a single day reads singular and the rest plural`() {
        assertEquals("1 day", dayCount(1))
        assertEquals("0 days", dayCount(0))
        assertEquals("2 days", dayCount(2))
    }
}
