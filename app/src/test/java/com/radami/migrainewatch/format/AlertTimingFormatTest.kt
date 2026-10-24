package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AlertTimingFormatTest {

    private companion object {
        /** +05:45, so a zone mix-up can't hide behind a whole-hour offset. */
        val KATHMANDU: ZoneId = ZoneId.of("Asia/Kathmandu")
        val UTC: ZoneId = ZoneId.of("UTC")
    }

    /** Monday 12:00 to Tuesday 03:30 in Kathmandu. */
    private val alert = AlertWindow(
        start = Instant.parse("2026-08-24T06:15:00Z"),
        end = Instant.parse("2026-08-24T21:45:00Z"),
        delta = 9f,
        direction = PressureDirection.DROP
    )

    @Test
    fun `an event ahead is named by its start in the given zone`() {
        assertEquals(
            "Starts Monday 12:00",
            formatAlertTiming(alert, AlertPhase.AHEAD, AlertTimingDetail.Brief, KATHMANDU)
        )
    }

    @Test
    fun `an event ahead leaves out its end even when there is room for it`() {
        assertEquals(
            "Starts Monday 12:00",
            formatAlertTiming(alert, AlertPhase.AHEAD, AlertTimingDetail.WithEnd, KATHMANDU)
        )
    }

    @Test
    fun `an event under way names when it eases, across midnight in the given zone`() {
        assertEquals(
            "Underway since Monday 12:00 · eases Tuesday 03:30",
            formatAlertTiming(alert, AlertPhase.UNDERWAY, AlertTimingDetail.WithEnd, KATHMANDU)
        )
    }

    @Test
    fun `the same event reads differently in another zone`() {
        assertEquals(
            "Underway since Monday 06:15 · eases Monday 21:45",
            formatAlertTiming(alert, AlertPhase.UNDERWAY, AlertTimingDetail.WithEnd, UTC)
        )
    }
}
