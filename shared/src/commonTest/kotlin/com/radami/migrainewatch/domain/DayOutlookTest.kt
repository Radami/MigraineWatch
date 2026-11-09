package com.radami.migrainewatch.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class DayOutlookTest {

    private val zone = TimeZone.UTC
    private val today = LocalDate(2026, 8, 23)

    /** A forecast reaching the end of the outlook, so no day is short of data. */
    private val fullForecast = today.plus(DayOutlook.DAYS, DateTimeUnit.DAY).atStartOfDayIn(zone)

    private fun alert(
        startDay: LocalDate,
        startHour: Int,
        endDay: LocalDate,
        endHour: Int,
        delta: Float = 9f,
        direction: PressureDirection = PressureDirection.DROP
    ) = AlertWindow(
        start = startDay.atStartOfDayIn(zone) + startHour.hours,
        end = endDay.atStartOfDayIn(zone) + endHour.hours,
        delta = delta,
        direction = direction
    )

    @Test
    fun `forecast spans the outlook starting today`() {
        val outlook = DayOutlook.forecast(emptyList(), fullForecast, today, zone)

        assertEquals(DayOutlook.DAYS, outlook.size)
        assertEquals(today, outlook.first().date)
        assertEquals(today.plus(6, DateTimeUnit.DAY), outlook.last().date)
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
        val alerts = listOf(alert(today.plus(1, DateTimeUnit.DAY), 20, today.plus(2, DateTimeUnit.DAY), 8))

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
        val shortForecast = today.plus(2, DateTimeUnit.DAY).atStartOfDayIn(zone) + 12.hours

        val outlook = DayOutlook.forecast(emptyList(), shortForecast, today, zone)

        assertEquals(OutlookRisk.Clear, outlook[0].risk)
        assertEquals(OutlookRisk.Clear, outlook[1].risk)
        assertEquals(OutlookRisk.Unknown, outlook[2].risk)
        assertEquals(OutlookRisk.Unknown, outlook.last().risk)
    }

    @Test
    fun `an event already known is elevated even where the forecast stops short`() {
        val shortForecast = today.atStartOfDayIn(zone) + 6.hours
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
