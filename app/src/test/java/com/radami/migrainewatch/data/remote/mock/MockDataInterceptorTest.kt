package com.radami.migrainewatch.data.remote.mock

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.preferences.AlertSensitivity
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import com.radami.migrainewatch.domain.AlertDetector
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The mock scenarios are calibrated against the alert sensitivity presets, so a change to
 * either one can silently make the demo data stop demonstrating anything. These tests run
 * the generated series through the real detector, exactly as the app does.
 */
class MockDataInterceptorTest {

    private companion object {
        // Berlin. Only the base pressure is seeded from lat+lon, so every assertion below
        // holds at any location — the point of the scenarios carrying no noise.
        const val LATITUDE = 52.52
        const val LONGITUDE = 13.41

        // The window AlertDetailViewModel charts around an event.
        const val CHART_HOURS_BEHIND = 24L
        const val CHART_HOURS_AHEAD = 60L
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @After
    fun tearDown() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS
    }

    /** Runs the interceptor over a forecast request; it answers without touching the network. */
    private fun alertInputReadings(): List<PressureReading> {
        val client = OkHttpClient.Builder().addInterceptor(MockDataInterceptor()).build()
        val request = Request.Builder()
            .url("https://api.open-meteo.com/v1/forecast?latitude=$LATITUDE&longitude=$LONGITUDE")
            .build()

        val body = client.newCall(request).execute().use { it.body!!.string() }
        val response = json.decodeFromString<OpenMeteoResponse>(body)

        val zone = ZoneId.of(response.timezone)
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val fetchedAt = Instant.now()
        val readings = response.hourly.time.mapIndexedNotNull { index, timeText ->
            val pressure = response.hourly.pressureMsl.getOrNull(index) ?: return@mapIndexedNotNull null
            val instant = LocalDateTime.parse(timeText, formatter).atZone(zone).toInstant()
            PressureReading(instant, pressure, pressure, fetchedAt)
        }

        // TodayViewModel detects over the last 24 h plus the forecast, so mirror that window.
        val from = fetchedAt.minus(24, ChronoUnit.HOURS)
        return readings.filter { !it.dateTime.isBefore(from) }
    }

    private fun alertCount(sensitivity: AlertSensitivity): Int =
        AlertDetector.detect(alertInputReadings(), sensitivity.thresholdHpa).size

    @Test
    fun `three-event scenario sheds one alert per step down in sensitivity`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS

        assertEquals("High", 3, alertCount(AlertSensitivity.HIGH))
        assertEquals("Medium", 2, alertCount(AlertSensitivity.MEDIUM))
        assertEquals("Low", 1, alertCount(AlertSensitivity.LOW))
    }

    @Test
    fun `three-event scenario alternates direction so events stay separate`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS

        val alerts = AlertDetector.detect(alertInputReadings(), AlertSensitivity.HIGH.thresholdHpa)

        assertEquals(listOf("drop", "rise", "drop"), alerts.map { it.direction })
        // Exact, not merely inside a band: the curve carries no noise, so anything else means
        // the anchors moved or the detector changed.
        assertEquals("first event", 12f, alerts[0].delta, 0.01f)
        assertEquals("second event", 9f, alerts[1].delta, 0.01f)
        assertEquals("third event", 7f, alerts[2].delta, 0.01f)
    }

    @Test
    fun `three-event scenario keeps every event inside the alert detail chart`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.THREE_EVENTS

        val now = Instant.now()
        val alerts = AlertDetector.detect(alertInputReadings(), AlertSensitivity.HIGH.thresholdHpa)

        // An event outside the charted window is listed above the chart with no band to point
        // at, which is what spreading the events over the full forecast used to cause.
        val chartFrom = now.minus(CHART_HOURS_BEHIND, ChronoUnit.HOURS)
        val chartTo = now.plus(CHART_HOURS_AHEAD, ChronoUnit.HOURS)
        alerts.forEach { alert ->
            assertTrue("$alert starts before the chart", !alert.start.isBefore(chartFrom))
            assertTrue("$alert ends after the chart", !alert.end.isAfter(chartTo))
        }

        // Back to back: each event's turning point is where the next one begins.
        alerts.zipWithNext().forEach { (earlier, later) ->
            assertEquals("$earlier should run into $later", earlier.end, later.start)
        }
    }

    @Test
    fun `two-event scenario alerts above Low but never at Low`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.TWO_EVENTS

        // UserJourneyTest walks the sensitivity down until the banner disappears, so the gap
        // between "always shown" and "never shown" is the property that matters here.
        assertEquals("High", 2, alertCount(AlertSensitivity.HIGH))
        assertEquals("Medium", 2, alertCount(AlertSensitivity.MEDIUM))
        assertEquals("Low", 0, alertCount(AlertSensitivity.LOW))

        val alerts = AlertDetector.detect(alertInputReadings(), AlertSensitivity.HIGH.thresholdHpa)
        assertEquals(listOf("drop", "rise"), alerts.map { it.direction })
        alerts.forEach { assertEquals("$it", 9f, it.delta, 0.01f) }
    }

    @Test
    fun `no-event scenario never alerts, even at the highest sensitivity`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NO_EVENTS

        assertEquals(0, alertCount(AlertSensitivity.HIGH))
    }
}
