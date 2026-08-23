package com.radami.migrainewatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayOutlookTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 8, 23)

    /** A forecast reaching the end of the outlook, so no day is short of data. */
    private val fullForecast = today.plusDays(DayOutlook.DAYS.toLong()).atStartOfDay(zone).toInstant()

    private fun alert(
        startDay: LocalDate,
        startHour: Int,
        endDay: LocalDate,
        endHour: Int,
        delta: Float = 9f,
        direction: PressureDirection = PressureDirection.DROP
    ) = AlertWindow(
        start = startDay.atStartOfDay(zone).plusHours(startHour.toLong()).toInstant(),
        end = endDay.atStartOfDay(zone).plusHours(endHour.toLong()).toInstant(),
        delta = delta,
        direction = direction
    )

    @Test
    fun `forecast spans the outlook starting today`() {
        val outlook = DayOutlook.forecast(emptyList(), fullForecast, today, zone)

        assertEquals(DayOutlook.DAYS, outlook.size)
        assertEquals(today, outlook.first().date)
        assertEquals(today.plusDays(6), outlook.last().date)
    }

    @Test
    fun `a covered day with no event is clear`() {
        val outlook = DayOutlook.forecast(emptyList(), fullForecast, today, zone)

        assertEquals(OutlookRisk.Clear, outlook.first().risk)
        assertNull(outlook.first().peakDelta)
        assertNull(outlook.first().direction)
    }

    @Test
    fun `every day an event touches is elevated`() {
        val alerts = listOf(alert(today.plusDays(1), 20, today.plusDays(2), 8))

        val outlook = DayOutlook.forecast(alerts, fullForecast, today, zone)

        assertEquals(OutlookRisk.Clear, outlook[0].risk)
        assertEquals(OutlookRisk.Elevated, outlook[1].risk)
        assertEquals(OutlookRisk.Elevated, outlook[2].risk)
        assertEquals(OutlookRisk.Clear, outlook[3].risk)
    }

    @Test
    fun `a day carries the largest of the events touching it`() {
        val alerts = listOf(
            alert(today, 1, today, 5, delta = 7f, direction = PressureDirection.DROP),
            alert(today, 9, today, 15, delta = 12f, direction = PressureDirection.RISE)
        )

        val outlook = DayOutlook.forecast(alerts, fullForecast, today, zone)

        assertEquals(12f, outlook.first().peakDelta!!, 0.01f)
        assertEquals(PressureDirection.RISE, outlook.first().direction)
    }

    @Test
    fun `days the forecast does not reach the end of are unknown`() {
        // Reaches midday on the day after tomorrow: that day is not fully covered.
        val shortForecast = today.plusDays(2).atStartOfDay(zone).plusHours(12).toInstant()

        val outlook = DayOutlook.forecast(emptyList(), shortForecast, today, zone)

        assertEquals(OutlookRisk.Clear, outlook[0].risk)
        assertEquals(OutlookRisk.Clear, outlook[1].risk)
        assertEquals(OutlookRisk.Unknown, outlook[2].risk)
        assertEquals(OutlookRisk.Unknown, outlook.last().risk)
    }

    @Test
    fun `an event already known is elevated even where the forecast stops short`() {
        val shortForecast = today.atStartOfDay(zone).plusHours(6).toInstant()
        val alerts = listOf(alert(today, 2, today, 5))

        val outlook = DayOutlook.forecast(alerts, shortForecast, today, zone)

        assertEquals(OutlookRisk.Elevated, outlook.first().risk)
    }

    @Test
    fun `no readings leaves every day unknown`() {
        val outlook = DayOutlook.forecast(emptyList(), null, today, zone)

        assertEquals(DayOutlook.DAYS, outlook.count { it.risk == OutlookRisk.Unknown })
    }
}
