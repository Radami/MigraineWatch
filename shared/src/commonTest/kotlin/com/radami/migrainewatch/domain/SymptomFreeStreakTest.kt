package com.radami.migrainewatch.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SymptomFreeStreakTest {

    private val today = LocalDate(2026, 8, 22)

    @Test
    fun `from returns null when nothing has been logged`() {
        assertNull(SymptomFreeStreak.from(emptyList(), today))
    }

    @Test
    fun `from returns null when only clear days have been logged`() {
        val entries = listOf(entry(daysAgo(3), Severity.CLEAR), entry(today, Severity.CLEAR))

        assertNull(SymptomFreeStreak.from(entries, today))
    }

    @Test
    fun `current streak counts the days since the last event`() {
        val entries = listOf(entry(daysAgo(5), Severity.MIGRAINE))

        val streak = SymptomFreeStreak.from(entries, today)!!

        assertEquals(5L, streak.currentDays)
        assertEquals(daysAgo(5), streak.lastEvent.date)
        assertEquals(Severity.MIGRAINE, streak.lastEvent.severity)
    }

    @Test
    fun `an event today leaves a current streak of zero`() {
        val entries = listOf(entry(today, Severity.AURA))

        assertEquals(0L, SymptomFreeStreak.from(entries, today)!!.currentDays)
    }

    @Test
    fun `longest needs a second event before there is a gap to measure`() {
        val entries = listOf(entry(daysAgo(5), Severity.MILD))

        assertNull(SymptomFreeStreak.from(entries, today)!!.longest)
    }

    @Test
    fun `longest is the widest gap between consecutive events`() {
        val entries = listOf(
            entry(daysAgo(40), Severity.MIGRAINE),
            entry(daysAgo(29), Severity.MILD),  // 10-day gap
            entry(daysAgo(28), Severity.AURA),  // back to back
            entry(daysAgo(3), Severity.MIGRAINE)   // 24-day gap
        )

        val longest = SymptomFreeStreak.from(entries, today)!!.longest!!

        assertEquals(24L, longest.days)
        assertEquals(daysAgo(27), longest.from)
        assertEquals(daysAgo(4), longest.to)
    }

    @Test
    fun `back to back events leave a gap of zero`() {
        val entries = listOf(
            entry(daysAgo(1), Severity.MILD),
            entry(today, Severity.MIGRAINE)
        )

        assertEquals(0L, SymptomFreeStreak.from(entries, today)!!.longest!!.days)
    }

    @Test
    fun `clear days do not break a streak`() {
        val entries = listOf(
            entry(daysAgo(20), Severity.MIGRAINE),
            entry(daysAgo(10), Severity.CLEAR),
            entry(daysAgo(9), Severity.MIGRAINE)
        )

        assertEquals(10L, SymptomFreeStreak.from(entries, today)!!.longest!!.days)
    }

    @Test
    fun `the running streak counts as longest once it beats every past gap`() {
        val entries = listOf(
            entry(daysAgo(35), Severity.MIGRAINE),
            entry(daysAgo(30), Severity.MIGRAINE)  // 4-day gap
        )

        val streak = SymptomFreeStreak.from(entries, today)!!

        assertEquals(30L, streak.currentDays)
        assertEquals(30L, streak.longest!!.days)
        assertEquals(daysAgo(29), streak.longest!!.from)
        assertEquals(today, streak.longest!!.to)
    }

    @Test
    fun `a tie stays credited to the run that set the record first`() {
        val entries = listOf(
            entry(daysAgo(17), Severity.MIGRAINE),
            entry(daysAgo(8), Severity.MIGRAINE)  // 8-day gap, matched by the running run
        )

        val streak = SymptomFreeStreak.from(entries, today)!!

        assertEquals(8L, streak.currentDays)
        assertEquals(8L, streak.longest!!.days)
        assertEquals(daysAgo(16), streak.longest!!.from)
        assertEquals(daysAgo(9), streak.longest!!.to)
    }

    @Test
    fun `entries are ordered before gaps are measured`() {
        // The DAO hands entries back newest first, so the calculation must not trust the order.
        val entries = listOf(
            entry(daysAgo(2), Severity.MIGRAINE),
            entry(daysAgo(12), Severity.MIGRAINE),
            entry(daysAgo(20), Severity.MILD)
        )

        val streak = SymptomFreeStreak.from(entries, today)!!

        assertEquals(2L, streak.currentDays)
        assertEquals(9L, streak.longest!!.days)
    }

    private fun entry(date: LocalDate, severity: Severity) = SymptomDay(date = date, severity = severity)

    private fun daysAgo(days: Int) = today.minus(days, DateTimeUnit.DAY)
}
