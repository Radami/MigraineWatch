package com.example.migrainetracker.data.remote.mock

import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.preferences.AlertSensitivity
import com.example.migrainetracker.data.remote.dto.OpenMeteoResponse
import com.example.migrainetracker.domain.AlertDetector
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
        // Berlin. The sine that adds texture to the series is seeded from lat+lon, so the
        // location fixes the phase the assertions below see.
        const val LATITUDE = 52.52
        const val LONGITUDE = 13.41
    }

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @After
    fun tearDown() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NORMAL
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
    fun `normal scenario sheds one alert per step down in sensitivity`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NORMAL

        assertEquals("High", 3, alertCount(AlertSensitivity.HIGH))
        assertEquals("Medium", 2, alertCount(AlertSensitivity.MEDIUM))
        assertEquals("Low", 1, alertCount(AlertSensitivity.LOW))
    }

    @Test
    fun `normal scenario alternates direction so events stay separate`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.NORMAL

        val alerts = AlertDetector.detect(alertInputReadings(), AlertSensitivity.HIGH.thresholdHpa)

        assertEquals(listOf("drop", "rise", "drop"), alerts.map { it.direction })
        // Each event has to sit inside its own band, otherwise the counts above are luck.
        assertTrue("first event: ${alerts[0].delta}", alerts[0].delta >= 10f)
        assertTrue("second event: ${alerts[1].delta}", alerts[1].delta >= 8f && alerts[1].delta < 10f)
        assertTrue("third event: ${alerts[2].delta}", alerts[2].delta >= 6f && alerts[2].delta < 8f)
    }

    @Test
    fun `storm scenario alerts below the low preset only`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.STORM

        assertTrue(alertCount(AlertSensitivity.HIGH) > 0)
        assertEquals(0, alertCount(AlertSensitivity.LOW))
    }

    @Test
    fun `calm scenario never alerts, even at the highest sensitivity`() {
        MockDataInterceptor.currentScenario = MockDataInterceptor.Scenario.CALM

        assertEquals(0, alertCount(AlertSensitivity.HIGH))
    }
}
