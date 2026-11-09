package com.radami.migrainewatch.format

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * Pins every format's exact output: the strings the Android formatters produced before the
 * formats moved here, so both platforms keep reading the same.
 */
class AppDateFormatsTest {

    private companion object {
        /** A Saturday afternoon with a one-digit day, as the format docs use. */
        val SATURDAY_AFTERNOON = LocalDateTime(2026, 8, 1, 14, 0)

        /** A two-digit day just after midnight, in a month whose short name is a common trap. */
        val SEPTEMBER_NIGHT = LocalDateTime(2026, 9, 23, 0, 5)

        val TUESDAY_NOON = LocalDateTime(2026, 9, 22, 12, 0)
        val WEDNESDAY_MORNING = LocalDateTime(2026, 9, 23, 9, 30)
    }

    @Test
    fun `full date and time`() {
        assertEquals("Saturday, 1 August 2026, 14:00", AppDateFormats.FULL_DATE_TIME.format(SATURDAY_AFTERNOON))
        assertEquals("Wednesday, 23 September 2026, 00:05", AppDateFormats.FULL_DATE_TIME.format(SEPTEMBER_NIGHT))
    }

    @Test
    fun `full date`() {
        assertEquals("Saturday, 1 August 2026", AppDateFormats.FULL_DATE.format(SATURDAY_AFTERNOON.date))
    }

    @Test
    fun `day and month with and without the year`() {
        assertEquals("Saturday 1 August", AppDateFormats.DAY_AND_MONTH.format(SATURDAY_AFTERNOON.date))
        assertEquals("Wednesday 23 September 2026", AppDateFormats.DAY_MONTH_AND_YEAR.format(SEPTEMBER_NIGHT.date))
    }

    @Test
    fun `month and year`() {
        assertEquals("September 2026", AppDateFormats.MONTH_AND_YEAR.format(SEPTEMBER_NIGHT.date))
    }

    @Test
    fun `short day and month with and without the year`() {
        assertEquals("1 Aug", AppDateFormats.SHORT_DAY_AND_MONTH.format(SATURDAY_AFTERNOON.date))
        assertEquals("23 Sep", AppDateFormats.SHORT_DAY_AND_MONTH.format(SEPTEMBER_NIGHT.date))
        assertEquals("23 Sep 2026", AppDateFormats.SHORT_DATE_AND_YEAR.format(SEPTEMBER_NIGHT.date))
    }

    @Test
    fun `day and time`() {
        assertEquals("Sat 1 Aug, 14:00", AppDateFormats.DAY_AND_TIME.format(SATURDAY_AFTERNOON))
        assertEquals("Wed 23 Sep, 00:05", AppDateFormats.DAY_AND_TIME.format(SEPTEMBER_NIGHT))
    }

    @Test
    fun `weekday alone and with the time`() {
        assertEquals("Sat", AppDateFormats.WEEKDAY.format(SATURDAY_AFTERNOON.date))
        assertEquals("Saturday 14:00", AppDateFormats.WEEKDAY_AND_TIME.format(SATURDAY_AFTERNOON))
        assertEquals("Wednesday 09:30", AppDateFormats.WEEKDAY_AND_TIME.format(WEDNESDAY_MORNING))
    }

    @Test
    fun `hour on a twelve hour clock`() {
        assertEquals("2PM", AppDateFormats.HOUR.format(SATURDAY_AFTERNOON))
        assertEquals("12AM", AppDateFormats.HOUR.format(SEPTEMBER_NIGHT))
        assertEquals("12PM", AppDateFormats.HOUR.format(TUESDAY_NOON))
        assertEquals("9AM", AppDateFormats.HOUR.format(WEDNESDAY_MORNING))
    }

    @Test
    fun `an instant is formatted as it reads in the given zone`() {
        // 08:15 UTC is 14:00 in Kathmandu (+05:45).
        val instant = Instant.parse("2026-08-01T08:15:00Z")

        assertEquals("Saturday 14:00", AppDateFormats.WEEKDAY_AND_TIME.format(instant, TimeZone.of("Asia/Kathmandu")))
    }
}
