package com.radami.migrainewatch.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ChartWindowTest {

    private companion object {
        val ZONE: TimeZone = TimeZone.of("Europe/Berlin")

        /** 14:37 local, so nothing lands on a step boundary by accident. */
        val NOW: Instant = LocalDateTime(2026, 8, 22, 14, 37).toInstant(ZONE)

        const val HOUR = 3600L

        fun alert(start: Instant, end: Instant) =
            AlertWindow(start = start, end = end, delta = 6f, direction = PressureDirection.DROP)
    }

    @Test
    fun `hourly step floors the anchor to a step boundary`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        assertEquals(0L, window.anchorEpochSecond % (3 * HOUR))
        assertTrue(window.anchorEpochSecond <= NOW.epochSeconds)
        assertTrue(NOW.epochSeconds - window.anchorEpochSecond < 3 * HOUR)
    }

    @Test
    fun `daily step anchors on local noon`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        val anchor = Instant.fromEpochSeconds(window.anchorEpochSecond).toLocalDateTime(ZONE)
        assertEquals(12, anchor.hour)
        assertEquals(0, anchor.minute)
        assertEquals(NOW.toLocalDateTime(ZONE).date, anchor.date)
    }

    @Test
    fun `points run three steps back and four ahead of the anchor`() {
        val window = ChartWindow.around(NOW, ChartStep.SixHours, ZONE)
        val anchor = window.anchorEpochSecond

        assertEquals(anchor, window.epochSecondAt(ChartWindow.ANCHOR_INDEX))
        assertEquals(anchor - 3 * 6 * HOUR, window.epochSecondAt(ChartWindow.POINT_INDICES.first))
        assertEquals(anchor + 4 * 6 * HOUR, window.epochSecondAt(ChartWindow.POINT_INDICES.last))
    }

    @Test
    fun `x is the anchor index at the anchor and fractional between points`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val anchor = Instant.fromEpochSeconds(window.anchorEpochSecond)

        assertEquals(3f, window.xOf(anchor), 0.0001f)
        assertEquals(4.5f, window.xOf(anchor + (4 * HOUR + 1800).seconds), 0.0001f)
        assertEquals(0f, window.xOf(anchor - (9 * HOUR).seconds), 0.0001f)
    }

    @Test
    fun `an instant read back from an x round-trips`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        listOf(0f, 3f, 4.5f, 7f).forEach { x ->
            assertEquals(x, window.xOf(window.instantAt(x)), 0.0001f)
        }
    }

    @Test
    fun `an x on a point is that point's own instant`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        ChartWindow.POINT_INDICES.forEach { i ->
            assertEquals(window.epochSecondAt(i), window.instantAt(i.toFloat()).epochSeconds)
        }
    }

    @Test
    fun `an x beyond the outer points still names an instant`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)
        val step = ChartStep.OneDay.seconds

        // The daily plot reaches half a step past each outer point, and the chart has to be
        // able to ask what is out there; clamping would hide the overhang rather than draw it.
        val firstIndex = ChartWindow.POINT_INDICES.first
        val lastIndex = ChartWindow.POINT_INDICES.last

        assertEquals(
            window.epochSecondAt(firstIndex) - step / 2,
            window.instantAt(firstIndex - 0.5f).epochSeconds
        )
        assertEquals(
            window.epochSecondAt(lastIndex) + step / 2,
            window.instantAt(lastIndex + 0.5f).epochSeconds
        )
    }

    @Test
    fun `x runs past the last point for an event beyond the window`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val beyond = Instant.fromEpochSeconds(window.anchorEpochSecond + 24 * HOUR)

        // Clipping is the drawing code's job, so the mapping itself must not clamp.
        assertTrue(window.xOf(beyond) > ChartWindow.POINT_INDICES.last)
    }

    @Test
    fun `an event inside the window is covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val anchor = Instant.fromEpochSeconds(window.anchorEpochSecond)

        assertTrue(window.covers(alert(anchor + HOUR.seconds, anchor + (5 * HOUR).seconds)))
    }

    @Test
    fun `an event overlapping an edge is covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val overLeft = alert(window.firstVisible - (48 * HOUR).seconds, window.firstVisible + HOUR.seconds)
        val overRight = alert(window.lastVisible - HOUR.seconds, window.lastVisible + (48 * HOUR).seconds)
        val spanning = alert(window.firstVisible - HOUR.seconds, window.lastVisible + HOUR.seconds)

        assertTrue(window.covers(overLeft))
        assertTrue(window.covers(overRight))
        assertTrue(window.covers(spanning))
    }

    @Test
    fun `an event outside the window is not covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val before = alert(window.firstVisible - (10 * HOUR).seconds, window.firstVisible - HOUR.seconds)
        val after = alert(window.lastVisible + HOUR.seconds, window.lastVisible + (10 * HOUR).seconds)

        assertFalse(window.covers(before))
        assertFalse(window.covers(after))
    }

    @Test
    fun `an event that only touches an edge is not covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val endsAtStart = alert(window.firstVisible - HOUR.seconds, window.firstVisible)
        val startsAtEnd = alert(window.lastVisible, window.lastVisible + HOUR.seconds)

        // Either would draw a band of no width, which reads as shading that failed.
        assertFalse(window.covers(endsAtStart))
        assertFalse(window.covers(startsAtEnd))
    }

    @Test
    fun `the daily window reaches half a day past its outer points`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.first) - 12 * HOUR,
            window.firstVisible.epochSeconds
        )
        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.last) + 12 * HOUR,
            window.lastVisible.epochSeconds
        )
    }

    @Test
    fun `an hourly window stops at its outer points`() {
        val window = ChartWindow.around(NOW, ChartStep.SixHours, ZONE)

        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.first),
            window.firstVisible.epochSeconds
        )
        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.last),
            window.lastVisible.epochSeconds
        )
    }

    @Test
    fun `a wider range reaches an event a narrower one cannot`() {
        val hours24 = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val days7 = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        // Two days ahead: past the 24 hrs chip's 12 hours of forecast, well inside the 7 days chip.
        val event = alert(NOW + (48 * HOUR).seconds, NOW + (54 * HOUR).seconds)

        assertFalse(hours24.covers(event))
        assertTrue(days7.covers(event))
    }
}
