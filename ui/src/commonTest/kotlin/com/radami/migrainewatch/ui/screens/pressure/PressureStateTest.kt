package com.radami.migrainewatch.ui.screens.pressure

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.preferences.AppSettings
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.data.repository.RefreshState
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.PressureDirection
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * The Pressure screen's rules, run on every platform. The Android ViewModel tests drive the same
 * function through Room and coroutines; these pin it directly.
 */
class PressureStateTest {

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-23T12:00:00Z")

        val ZURICH = LocationData(source = "manual", name = "Zurich", timezone = "Europe/Zurich")
    }

    private fun reading(at: Instant, pressure: Float = 1015f, fetchedAt: Instant = NOW) =
        PressureReading(dateTime = at, pressureMsl = pressure, surfacePressure = 960f, fetchedDateTime = fetchedAt)

    private fun state(
        readings: List<PressureReading>,
        settings: AppSettings = AppSettings(location = ZURICH),
        alerts: List<AlertWindow> = emptyList()
    ) = pressureState(
        readings = readings,
        settings = settings,
        refreshState = RefreshState.Updated,
        alerts = alerts,
        now = NOW
    )

    @Test
    fun theCurrentPressureIsTheLastMeasuredReading() {
        val readings = listOf(
            reading(NOW - 2.hours, pressure = 1010f),
            reading(NOW - 1.hours, pressure = 1011f),
            reading(NOW + 1.hours, pressure = 1020f)
        )

        assertEquals(1011f, state(readings).currentPressure)
    }

    @Test
    fun beforeAnyMeasurementTheCurrentPressureIsTheEarliestForecast() {
        val readings = listOf(reading(NOW + 1.hours, pressure = 1012f), reading(NOW + 2.hours, pressure = 1013f))

        assertEquals(1012f, state(readings).currentPressure)
    }

    @Test
    fun aReadingAtExactlyNowIsStillForecast() {
        val readings = listOf(reading(NOW - 1.hours, pressure = 1009f), reading(NOW, pressure = 1014f))

        assertEquals(1009f, state(readings).currentPressure)
    }

    @Test
    fun withNoReadingsThereIsNoCurrentPressureOrUpdate() {
        val empty = state(emptyList())

        assertNull(empty.currentPressure)
        assertNull(empty.lastUpdated)
    }

    @Test
    fun lastUpdatedIsTheNewestFetch() {
        val readings = listOf(reading(NOW, fetchedAt = NOW - 3.hours), reading(NOW + 1.hours, fetchedAt = NOW - 1.hours))

        assertEquals(NOW - 1.hours, state(readings).lastUpdated)
    }

    @Test
    fun theChartIsDrawnInTheLocationsZoneAgainstTheUsersThreshold() {
        val settings = AppSettings(location = ZURICH, alertThresholdHpa = AlertSensitivity.HIGH.thresholdHpa)
        val pressure = state(listOf(reading(NOW)), settings = settings)

        assertEquals(TimeZone.of("Europe/Zurich"), pressure.zone)
        assertEquals("Zurich", pressure.locationName)
        assertEquals(AlertSensitivity.HIGH.thresholdHpa, pressure.alertThresholdHpa)
    }

    @Test
    fun finishedEventsStayOnTheChart() {
        val finished = AlertWindow(start = NOW - 10.hours, end = NOW - 2.hours, delta = 9f, direction = PressureDirection.DROP)

        assertEquals(listOf(finished), state(listOf(reading(NOW)), alerts = listOf(finished)).alertWindows)
    }
}
