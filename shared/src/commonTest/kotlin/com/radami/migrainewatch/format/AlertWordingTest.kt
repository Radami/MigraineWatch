package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.PressureDirection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the exact words a pressure warning is spelled in, so the wording reads the same on
 * every platform, including the rounding it once got from `java.util` formatting.
 */
class AlertWordingTest {

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
    fun `hPa below zero keeps its sign`() {
        assertEquals("-8.3", formatHpa(-8.25f))
    }
}
