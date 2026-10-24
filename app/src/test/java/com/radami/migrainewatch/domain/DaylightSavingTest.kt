package com.radami.migrainewatch.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Days that are not 24 hours long. Every expected instant is written out in UTC rather than
 * derived, so a day boundary computed as "midnight plus 24 hours" can't agree with itself.
 */
class DaylightSavingTest {

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /**
         * Chile springs forward at midnight, so 6 September 2026 has no 00:00: the day starts
         * at 01:00, which is 04:00 UTC, the same instant the 5th ended.
         */
        val SANTIAGO: ZoneId = ZoneId.of("America/Santiago")

        /** 23 hours long: 2026-03-28T23:00Z to 2026-03-29T22:00Z. */
        val SPRING_FORWARD: LocalDate = LocalDate.of(2026, 3, 29)

        /** 25 hours long: 2026-10-24T22:00Z to 2026-10-25T23:00Z. */
        val FALL_BACK: LocalDate = LocalDate.of(2026, 10, 25)

        val CHILE_DAY_BEFORE_GAP: LocalDate = LocalDate.of(2026, 9, 5)
        val CHILE_DAY_WITHOUT_MIDNIGHT: LocalDate = LocalDate.of(2026, 9, 6)

        /** Local noon, where the daily chart anchors. */
        const val NOON = 12
    }

    private fun alert(start: String, end: String) = AlertWindow(
        start = Instant.parse(start),
        end = Instant.parse(end),
        delta = 9f,
        direction = PressureDirection.DROP
    )

    private fun firstOutlookDay(today: LocalDate, coveredThrough: String, zone: ZoneId) =
        DayOutlook.forecast(emptyList(), Instant.parse(coveredThrough), today, zone).first()

    // --- DayOutlook coverage -----------------------------------------------------------------

    @Test
    fun `a 23-hour day is clear once coverage reaches its real end`() {
        val day = firstOutlookDay(SPRING_FORWARD, "2026-03-29T22:00:00Z", BERLIN)

        assertEquals(OutlookRisk.Clear, day.risk)
    }

    @Test
    fun `a 25-hour day is not clear after only 24 hours of coverage`() {
        val day = firstOutlookDay(FALL_BACK, "2026-10-25T22:00:00Z", BERLIN)

        assertEquals(OutlookRisk.Unknown, day.risk)
    }

    @Test
    fun `a 25-hour day is clear once coverage reaches its real end`() {
        val day = firstOutlookDay(FALL_BACK, "2026-10-25T23:00:00Z", BERLIN)

        assertEquals(OutlookRisk.Clear, day.risk)
    }

    @Test
    fun `the day before a missing midnight ends where the next day starts`() {
        assertEquals(
            OutlookRisk.Unknown,
            firstOutlookDay(CHILE_DAY_BEFORE_GAP, "2026-09-06T03:59:59Z", SANTIAGO).risk
        )
        assertEquals(
            OutlookRisk.Clear,
            firstOutlookDay(CHILE_DAY_BEFORE_GAP, "2026-09-06T04:00:00Z", SANTIAGO).risk
        )
    }

    // --- daysTouched ---------------------------------------------------------------------------

    @Test
    fun `an event ending at the midnight after a 25-hour day stays on that day`() {
        val span = AlertDetector.daysTouched(alert("2026-10-25T10:00:00Z", "2026-10-25T23:00:00Z"), BERLIN)

        assertEquals(FALL_BACK, span.start)
        assertEquals(FALL_BACK, span.endInclusive)
    }

    @Test
    fun `an event in the extra hour of a 25-hour day is on that day`() {
        // 23:00 to 23:30 local on the 25th, which only exists because the clocks went back.
        val span = AlertDetector.daysTouched(alert("2026-10-25T22:00:00Z", "2026-10-25T22:30:00Z"), BERLIN)

        assertEquals(FALL_BACK, span.start)
        assertEquals(FALL_BACK, span.endInclusive)
    }

    @Test
    fun `an event ending at the midnight after a 23-hour day stays on that day`() {
        val span = AlertDetector.daysTouched(alert("2026-03-29T10:00:00Z", "2026-03-29T22:00:00Z"), BERLIN)

        assertEquals(SPRING_FORWARD, span.endInclusive)
    }

    @Test
    fun `an event ending where a day without midnight starts stays on the day before`() {
        val span = AlertDetector.daysTouched(alert("2026-09-05T20:00:00Z", "2026-09-06T04:00:00Z"), SANTIAGO)

        assertEquals(CHILE_DAY_BEFORE_GAP, span.start)
        assertEquals(CHILE_DAY_BEFORE_GAP, span.endInclusive)
    }

    @Test
    fun `an event running into a day without midnight touches it`() {
        val span = AlertDetector.daysTouched(alert("2026-09-05T20:00:00Z", "2026-09-06T04:30:00Z"), SANTIAGO)

        assertEquals(CHILE_DAY_WITHOUT_MIDNIGHT, span.endInclusive)
    }

    @Test
    fun `eventDays marks a day without midnight`() {
        val days = AlertDetector.eventDays(listOf(alert("2026-09-06T04:00:00Z", "2026-09-06T06:00:00Z")), SANTIAGO)

        assertEquals(setOf(CHILE_DAY_WITHOUT_MIDNIGHT), days)
    }

    // --- ChartWindow daily anchor --------------------------------------------------------------

    @Test
    fun `the daily anchor is local noon on the day the clocks go back`() {
        // 14:37 CET on the 25th.
        val window = ChartWindow.around(Instant.parse("2026-10-25T13:37:00Z"), ChartStep.OneDay, BERLIN)

        assertEquals(Instant.parse("2026-10-25T11:00:00Z").epochSecond, window.anchorEpochSecond)
    }

    @Test
    fun `the daily anchor is local noon on the day the clocks go forward`() {
        // 14:37 CEST on the 29th.
        val window = ChartWindow.around(Instant.parse("2026-03-29T12:37:00Z"), ChartStep.OneDay, BERLIN)

        assertEquals(Instant.parse("2026-03-29T10:00:00Z").epochSecond, window.anchorEpochSecond)
    }

    @Test
    fun `daily points across a clock change drift off noon but never onto another date`() {
        val window = ChartWindow.around(Instant.parse("2026-10-25T13:37:00Z"), ChartStep.OneDay, BERLIN)

        // Points before the change sit at 13:00 summer time, one hour off noon, on their own date.
        val expected = listOf(
            "2026-10-22T13:00", "2026-10-23T13:00", "2026-10-24T13:00", "2026-10-25T12:00",
            "2026-10-26T12:00", "2026-10-27T12:00", "2026-10-28T12:00", "2026-10-29T12:00"
        )
        val actual = ChartWindow.POINT_INDICES.map { i ->
            Instant.ofEpochSecond(window.epochSecondAt(i)).atZone(BERLIN).toLocalDateTime().toString()
        }

        assertEquals(expected, actual)
    }

    @Test
    fun `the daily anchor is local noon on a day without midnight`() {
        // 14:37 on the 6th in Santiago, now on summer time (UTC-3).
        val window = ChartWindow.around(Instant.parse("2026-09-06T17:37:00Z"), ChartStep.OneDay, SANTIAGO)

        val anchor = Instant.ofEpochSecond(window.anchorEpochSecond).atZone(SANTIAGO)
        assertEquals(CHILE_DAY_WITHOUT_MIDNIGHT, anchor.toLocalDate())
        assertEquals(NOON, anchor.hour)
    }
}
