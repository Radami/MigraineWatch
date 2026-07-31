package com.radami.migrainewatch.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertSensitivityTest {

    @Test
    fun `default is medium`() {
        assertEquals(AlertSensitivity.MEDIUM, AlertSensitivity.Default)
        assertEquals(8f, AlertSensitivity.Default.thresholdHpa, 0.001f)
    }

    @Test
    fun `settings fall back to the default threshold`() {
        assertEquals(
            AlertSensitivity.Default.thresholdHpa,
            AppSettings().alertThresholdHpa,
            0.001f
        )
        assertEquals(AlertSensitivity.MEDIUM, AppSettings().alertSensitivity)
    }

    @Test
    fun `each preset maps back to itself`() {
        AlertSensitivity.entries.forEach { sensitivity ->
            assertEquals(sensitivity, AlertSensitivity.forThreshold(sensitivity.thresholdHpa))
        }
    }

    @Test
    fun `thresholds saved by the old slider snap to the nearest preset`() {
        // The slider offered every whole value from 3 to 15, so stored settings can hold
        // anything in that range and must still resolve to one of the three presets.
        val expected = mapOf(
            3f to AlertSensitivity.HIGH,
            5f to AlertSensitivity.HIGH,
            6f to AlertSensitivity.HIGH,
            // Ties resolve to the more sensitive preset, since minBy keeps the first match
            // and the presets are declared most-sensitive first.
            7f to AlertSensitivity.HIGH,
            9f to AlertSensitivity.MEDIUM,
            11f to AlertSensitivity.LOW,
            15f to AlertSensitivity.LOW
        )

        expected.forEach { (stored, sensitivity) ->
            assertEquals("threshold $stored", sensitivity, AlertSensitivity.forThreshold(stored))
        }
    }
}
