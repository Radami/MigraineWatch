package com.radami.migrainewatch.ui.screens.calendar

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.domain.Severity
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The Calendar screen's rules, run on every platform. The Android ViewModel tests drive the same
 * functions through Room and coroutines; these pin them directly.
 */
class CalendarStateTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")

        val ZURICH: TimeZone = TimeZone.of("Europe/Zurich")

        /** UTC+14: already the next calendar day when it is noon in UTC. */
        val KIRITIMATI: TimeZone = TimeZone.of("Pacific/Kiritimati")

        const val THRESHOLD_HPA = 8f
    }

    private fun entry(date: LocalDate, severity: Severity = Severity.MIGRAINE) = SymptomEntry(
        date = date,
        severity = severity,
        triggers = emptyList(),
        durationBucket = null,
        reliefPercent = null,
        medication = null,
        notes = null,
        createdAt = NOW,
        updatedAt = NOW
    )

    private fun state(
        period: CalendarPeriod = calendarPeriod(0, ZURICH, NOW),
        entries: List<SymptomEntry> = emptyList(),
        readings: List<PressureReading> = emptyList(),
        statsEntries: List<SymptomEntry> = emptyList()
    ) = calendarState(period, entries, readings, statsEntries, THRESHOLD_HPA)

    @Test
    fun theCurrentMonthIsTheLocationsMonth() {
        // 31 Aug, 23:00 UTC is already 1 Sep in Zurich.
        val period = calendarPeriod(0, ZURICH, Instant.parse("2026-08-31T23:00:00Z"))

        assertEquals(YearMonth(2026, Month.SEPTEMBER), period.month)
        assertEquals(LocalDate(2026, 9, 1), period.today)
    }

    @Test
    fun anOffsetBrowsesAcrossYears() {
        assertEquals(YearMonth(2025, Month.DECEMBER), calendarPeriod(-8, ZURICH, NOW).month)
        assertEquals(YearMonth(2027, Month.JANUARY), calendarPeriod(5, ZURICH, NOW).month)
    }

    @Test
    fun browsingDoesNotMoveToday() {
        assertEquals(LocalDate(2026, 8, 23), calendarPeriod(-3, ZURICH, NOW).today)
    }

    @Test
    fun theReadingsSpanTheMonthsDaysAtTheLocation() {
        val period = calendarPeriod(0, ZURICH, NOW)

        // Zurich is on summer time (UTC+2) in August and on 1 September.
        assertEquals(Instant.parse("2026-07-31T22:00:00Z"), period.readingsFrom)
        assertEquals(Instant.parse("2026-08-31T22:00:00Z"), period.readingsUntil)
    }

    @Test
    fun theStatisticsCountBackAYearFromToday() {
        assertEquals(LocalDate(2025, 8, 23), calendarPeriod(0, ZURICH, NOW).statsFrom)
    }

    @Test
    fun entriesAreKeyedByTheirDay() {
        val migraine = entry(LocalDate(2026, 8, 4))

        assertEquals(mapOf(LocalDate(2026, 8, 4) to migraine), state(entries = listOf(migraine)).entriesInMonth)
    }

    @Test
    fun anEventIsMarkedOnTheDayItFallsOnAtTheLocation() {
        // A 12 hPa drop inside half an hour at 22:00 UTC: still the 11th in UTC, the 12th in Kiritimati.
        val start = Instant.parse("2026-08-11T22:00:00Z")
        val end = start + 30.minutes
        val readings = listOf(
            PressureReading(start, 1020f, 1020f, end),
            PressureReading(end, 1008f, 1008f, end)
        )

        val period = calendarPeriod(0, KIRITIMATI, NOW)

        assertEquals(setOf(LocalDate(2026, 8, 12)), state(period = period, readings = readings).highRiskDays)
    }

    @Test
    fun noEventMarksNoDays() {
        assertEquals(emptySet(), state().highRiskDays)
    }

    @Test
    fun theStatisticsCountThisMonthAndLastWhicheverMonthIsBrowsed() {
        val statsEntries = listOf(
            entry(LocalDate(2026, 8, 2), Severity.MIGRAINE),
            entry(LocalDate(2026, 8, 9), Severity.MIGRAINE),
            entry(LocalDate(2026, 8, 15), Severity.MILD),
            entry(LocalDate(2026, 7, 30), Severity.AURA),
            entry(LocalDate(2026, 1, 10), Severity.MIGRAINE)
        )

        val browsingMarch = state(period = calendarPeriod(-5, ZURICH, NOW), statsEntries = statsEntries)

        assertEquals(mapOf(Severity.MIGRAINE to 2, Severity.MILD to 1), browsingMarch.monthLogCounts)
        assertEquals(mapOf(Severity.AURA to 1), browsingMarch.lastMonthLogCounts)
        assertEquals(
            mapOf(Severity.MIGRAINE to 3, Severity.MILD to 1, Severity.AURA to 1),
            browsingMarch.last12MonthsLogCounts
        )
    }

    @Test
    fun lastMonthInJanuaryIsTheDecemberBefore() {
        val january = Instant.parse("2027-01-15T12:00:00Z")
        val statsEntries = listOf(entry(LocalDate(2026, 12, 31)))

        val counts = state(period = calendarPeriod(0, ZURICH, january), statsEntries = statsEntries)

        assertEquals(mapOf(Severity.MIGRAINE to 1), counts.lastMonthLogCounts)
        assertEquals(emptyMap(), counts.monthLogCounts)
    }
}
