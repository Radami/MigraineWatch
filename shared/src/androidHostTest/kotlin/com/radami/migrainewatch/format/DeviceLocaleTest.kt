package com.radami.migrainewatch.format

import com.radami.migrainewatch.domain.AlertPhase
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.datetime.TimeZone
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The app's text is English-only, so a device set to another language must not change it:
 * the decimal separator and the day names stay as written.
 */
class DeviceLocaleTest {

    private val deviceLocale: Locale = Locale.getDefault()

    @BeforeTest
    fun useAnotherDeviceLocale() {
        Locale.setDefault(Locale.GERMANY)
    }

    @AfterTest
    fun restoreDeviceLocale() {
        Locale.setDefault(deviceLocale)
    }

    @Test
    fun `hPa keeps a point for a decimal separator`() {
        assertEquals("8.2", formatHpa(8.2f))
    }

    @Test
    fun `timing keeps English day names`() {
        val alert = AlertWindow(
            start = Instant.parse("2026-08-24T06:15:00Z"),
            end = Instant.parse("2026-08-24T21:45:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        assertEquals(
            "Starts Monday 06:15",
            formatAlertTiming(alert, AlertPhase.AHEAD, AlertTimingDetail.Brief, TimeZone.UTC)
        )
    }
}
