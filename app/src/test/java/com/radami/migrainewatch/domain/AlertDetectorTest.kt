package com.radami.migrainewatch.domain

import com.radami.migrainewatch.data.model.PressureReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.sin

class AlertDetectorTest {

    private val now = Instant.parse("2023-10-01T12:00:00Z")

    @Test
    fun `detect returns alert when pressure drops more than threshold`() {
        val readings = listOf(
            PressureReading(now, 1020f, 1010f, now),
            PressureReading(now.plus(12, ChronoUnit.HOURS), 1010f, 1000f, now)
        )
        
        val alerts = AlertDetector.detect(readings, 5f)
        
        assertEquals(1, alerts.size)
        assertEquals("drop", alerts[0].direction)
        assertEquals(10f, alerts[0].delta, 0.01f)
    }

    @Test
    fun `detect returns empty list when pressure change is below threshold`() {
        val readings = listOf(
            PressureReading(now, 1020f, 1010f, now),
            PressureReading(now.plus(12, ChronoUnit.HOURS), 1018f, 1008f, now)
        )
        
        val alerts = AlertDetector.detect(readings, 5f)

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `eventDaysByDirection marks every day an alert spans`() {
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T06:00:00Z"),
            delta = 9f,
            direction = "drop"
        )

        val days = AlertDetector.eventDaysByDirection(listOf(alert), ZoneId.of("UTC"))

        assertEquals(
            mapOf(
                LocalDate.parse("2023-10-01") to "drop",
                LocalDate.parse("2023-10-02") to "drop",
                LocalDate.parse("2023-10-03") to "drop"
            ),
            days
        )
    }

    @Test
    fun `eventDaysByDirection reports the direction that dominates a shared day`() {
        // A drop ending in the morning and a rise running the rest of the day: the day cell
        // has room for one arrow, so the longer of the two wins.
        val drop = AlertWindow(
            start = Instant.parse("2023-10-01T22:00:00Z"),
            end = Instant.parse("2023-10-02T04:00:00Z"),
            delta = 8f,
            direction = "drop"
        )
        val rise = AlertWindow(
            start = Instant.parse("2023-10-02T06:00:00Z"),
            end = Instant.parse("2023-10-02T22:00:00Z"),
            delta = 8f,
            direction = "rise"
        )

        val days = AlertDetector.eventDaysByDirection(listOf(drop, rise), ZoneId.of("UTC"))

        assertEquals("drop", days[LocalDate.parse("2023-10-01")])
        assertEquals("rise", days[LocalDate.parse("2023-10-02")])
    }

    @Test
    fun `eventDaysByDirection returns nothing when there are no alerts`() {
        assertTrue(AlertDetector.eventDaysByDirection(emptyList(), ZoneId.of("UTC")).isEmpty())
    }

    @Test
    fun `detect collapses noisy overlapping windows into distinct alerts`() {
        // Regression test for a crash on the alert screen: historical rows persist across
        // refreshes while the mock pattern is re-anchored to "now", stitching two series
        // together. On such data, neighbouring sliding windows used to flip direction label,
        // escape the merge step, and get pinned to the same pressure extremes — producing
        // duplicate alerts (identical start times) that crashed the LazyColumn keyed on them.
        val seed = 128.242
        val base = 1013f + (seed % 5.0).toFloat()
        fun eventOffset(j: Int): Float = when {
            j < -12 -> 0f
            j <= 12 -> -15f * (j + 12) / 24f
            j <= 14 -> -15f
            j <= 38 -> -15f + 15f * (j - 14) / 24f
            j <= 40 -> 0f
            j <= 64 -> -15f * (j - 40) / 24f
            else -> -15f
        }
        val readings = (-23..168).map { i ->
            // The historical half comes from a fetch 8 h earlier, so its pattern is shifted.
            val j = if (i < 0) i + 8 else i
            val pressure = base + 2f * sin(j / 10.0 + seed).toFloat() + eventOffset(j)
            PressureReading(now.plus(i.toLong(), ChronoUnit.HOURS), pressure, pressure, now)
        }

        val alerts = AlertDetector.detect(readings, 10f)

        // Every alert must have a unique start (LazyColumn key requirement)...
        assertEquals(alerts.size, alerts.map { it.start }.distinct().size)
        // ...same-direction alerts must not overlap...
        alerts.zipWithNext().forEach { (a, b) ->
            assertTrue(a.direction != b.direction || a.end.isBefore(b.start))
        }
        // ...and the three mock events survive as alternating drop / rise / drop.
        assertEquals(listOf("drop", "rise", "drop"), alerts.map { it.direction })
    }

}
