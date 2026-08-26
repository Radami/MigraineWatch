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
    private val utc = ZoneId.of("UTC")

    @Test
    fun `detect returns alert when pressure drops more than threshold`() {
        val readings = listOf(
            PressureReading(now, 1020f, 1010f, now),
            PressureReading(now.plus(12, ChronoUnit.HOURS), 1010f, 1000f, now)
        )
        
        val alerts = AlertDetector.detect(readings, 5f)
        
        assertEquals(1, alerts.size)
        assertEquals(PressureDirection.DROP, alerts[0].direction)
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

    /**
     * [AlertDetector.daysTouched] is tested directly as well as through `eventDays`, because
     * the outlook asks it a different question: `eventDays` walks the range, while
     * `date in daysTouched(...)` leans on its endpoints. A range whose endpoints were wrong in
     * a way the walk happened to absorb would pass one and fail the other.
     */
    @Test
    fun `daysTouched spans an alert from its first day to its last`() {
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T06:00:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        val span = AlertDetector.daysTouched(alert, utc)

        assertEquals(LocalDate.of(2023, 10, 1), span.start)
        assertEquals(LocalDate.of(2023, 10, 3), span.endInclusive)
    }

    @Test
    fun `daysTouched contains every day between the endpoints and nothing outside them`() {
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T06:00:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        val span = AlertDetector.daysTouched(alert, utc)

        assertTrue(LocalDate.of(2023, 9, 30) !in span)
        assertTrue(LocalDate.of(2023, 10, 1) in span)
        assertTrue(LocalDate.of(2023, 10, 2) in span)
        assertTrue(LocalDate.of(2023, 10, 3) in span)
        assertTrue(LocalDate.of(2023, 10, 4) !in span)
    }

    @Test
    fun `daysTouched drops a day the alert only reaches at midnight`() {
        // Ends exactly as the 3rd begins, so it spends no time in the 3rd.
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T00:00:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        val span = AlertDetector.daysTouched(alert, utc)

        assertEquals(LocalDate.of(2023, 10, 2), span.endInclusive)
        assertTrue(LocalDate.of(2023, 10, 3) !in span)
    }

    @Test
    fun `daysTouched keeps a day the alert both starts and ends on at midnight`() {
        // Dropping the end day here would leave the event touching no day at all.
        val alert = AlertWindow(
            start = Instant.parse("2023-10-02T00:00:00Z"),
            end = Instant.parse("2023-10-02T00:00:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        val span = AlertDetector.daysTouched(alert, utc)

        assertEquals(LocalDate.of(2023, 10, 2), span.start)
        assertTrue(LocalDate.of(2023, 10, 2) in span)
    }

    @Test
    fun `daysTouched reads the days in the zone it is given`() {
        // 23:00 UTC on the 1st is already the 2nd in Berlin, so the same instant lands on a
        // different day depending on the zone the outlook and the calendar are drawn in.
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T23:00:00Z"),
            end = Instant.parse("2023-10-01T23:30:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        assertEquals(LocalDate.of(2023, 10, 1), AlertDetector.daysTouched(alert, utc).start)
        assertEquals(
            LocalDate.of(2023, 10, 2),
            AlertDetector.daysTouched(alert, ZoneId.of("Europe/Berlin")).start
        )
    }

    @Test
    fun `eventDays marks every day an alert spans`() {
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T06:00:00Z"),
            delta = 9f,
            direction = PressureDirection.DROP
        )

        val days = AlertDetector.eventDays(listOf(alert), ZoneId.of("UTC"))

        assertEquals(
            setOf(
                LocalDate.parse("2023-10-01"),
                LocalDate.parse("2023-10-02"),
                LocalDate.parse("2023-10-03")
            ),
            days
        )
    }

    @Test
    fun `eventDays marks both days when opposite directions share one`() {
        // A drop ending in the morning and a rise running the rest of the day. Both are
        // qualifying events, so every day either one touches is high risk.
        val drop = AlertWindow(
            start = Instant.parse("2023-10-01T22:00:00Z"),
            end = Instant.parse("2023-10-02T04:00:00Z"),
            delta = 8f,
            direction = PressureDirection.DROP
        )
        val rise = AlertWindow(
            start = Instant.parse("2023-10-02T06:00:00Z"),
            end = Instant.parse("2023-10-02T22:00:00Z"),
            delta = 8f,
            direction = PressureDirection.RISE
        )

        val days = AlertDetector.eventDays(listOf(drop, rise), ZoneId.of("UTC"))

        assertEquals(
            setOf(LocalDate.parse("2023-10-01"), LocalDate.parse("2023-10-02")),
            days
        )
    }

    @Test
    fun `eventDays ignores the day an alert ends on at midnight`() {
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T18:00:00Z"),
            end = Instant.parse("2023-10-03T00:00:00Z"),
            delta = 6f,
            direction = PressureDirection.DROP
        )

        val days = AlertDetector.eventDays(listOf(alert), ZoneId.of("UTC"))

        assertEquals(
            setOf(LocalDate.parse("2023-10-01"), LocalDate.parse("2023-10-02")),
            days
        )
    }

    @Test
    fun `eventDays keeps a single day alert that ends at midnight`() {
        // Trimming the end day must not empty a window that starts and ends on the same date.
        val alert = AlertWindow(
            start = Instant.parse("2023-10-01T00:00:00Z"),
            end = Instant.parse("2023-10-01T00:00:00Z"),
            delta = 6f,
            direction = PressureDirection.DROP
        )

        val days = AlertDetector.eventDays(listOf(alert), ZoneId.of("UTC"))

        assertEquals(setOf(LocalDate.parse("2023-10-01")), days)
    }

    @Test
    fun `eventDays returns nothing when there are no alerts`() {
        assertTrue(AlertDetector.eventDays(emptyList(), ZoneId.of("UTC")).isEmpty())
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
        assertEquals(
            listOf(PressureDirection.DROP, PressureDirection.RISE, PressureDirection.DROP),
            alerts.map { it.direction }
        )
    }

}
