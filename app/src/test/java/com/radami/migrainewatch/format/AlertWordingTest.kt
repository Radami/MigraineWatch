package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Instant

/**
 * Pins the exact words a pressure warning is spelled in. The wording is moving to the shared
 * module, where it can no longer lean on `java.util` formatting, so these hold the output
 * steady across that move, including the rounding and locale behaviour it gets for free today.
 */
class AlertWordingTest {

    private val deviceLocale: Locale = Locale.getDefault()

    @Before
    fun useAnotherDeviceLocale() {
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restoreDeviceLocale() {
        Locale.setDefault(deviceLocale)
    }

    @Test
    fun `a direction is spelled in lower case`() {
        assertEquals("pressure drop", PressureDirection.DROP.label)
        assertEquals("pressure rise", PressureDirection.RISE.label)
    }

    @Test
    fun `a summary names the direction and the swing over 24 hours`() {
        assertEquals("pressure drop (9.0 hPa in 24h)", formatAlertSummary(9f, PressureDirection.DROP))
        assertEquals("pressure rise (8.2 hPa in 24h)", formatAlertSummary(8.2f, PressureDirection.RISE))
    }

    @Test
    fun `a headline is the summary with its first letter capitalised`() {
        assertEquals("Pressure drop (9.0 hPa in 24h)", formatAlertHeadline(9f, PressureDirection.DROP))
        assertEquals("Pressure rise (8.2 hPa in 24h)", formatAlertHeadline(8.2f, PressureDirection.RISE))
    }

    @Test
    fun `hPa is written to one decimal`() {
        assertEquals("0.0", formatHpa(0f))
        assertEquals("9.0", formatHpa(9f))
        assertEquals("7.0", formatHpa(7.04f))
        assertEquals("12.3", formatHpa(12.34f))
    }

    @Test
    fun `hPa rounds half up on the decimal value it prints`() {
        assertEquals("8.3", formatHpa(8.25f))
        assertEquals("0.1", formatHpa(0.05f))
        assertEquals("7.3", formatHpa(7.35f))
    }

    @Test
    fun `hPa keeps a point for a decimal separator whatever the device locale`() {
        assertEquals("8.2", formatHpa(8.2f))
    }

    @Test
    fun `timing keeps English day names whatever the device locale`() {
        val alert = AlertWindow(
            start = Instant.parse("2026-08-24T06:15:00Z"),
            end = Instant.parse("2026-08-24T21:45:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        assertEquals(
            "Starts Monday 06:15",
            formatAlertTiming(alert, AlertPhase.AHEAD, AlertTimingDetail.Brief, ZoneId.of("UTC"))
        )
    }
}
