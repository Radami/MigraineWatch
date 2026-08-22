package com.radami.migrainewatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ChartWindowTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

        /** 14:37 local, so nothing lands on a step boundary by accident. */
        val NOW: Instant = ZonedDateTime.of(2026, 8, 22, 14, 37, 0, 0, ZONE).toInstant()

        const val HOUR = 3600L

        fun alert(start: Instant, end: Instant) =
            AlertWindow(start = start, end = end, delta = 6f, direction = PressureDirection.DROP)
    }

    @Test
    fun `hourly step floors the anchor to a step boundary`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        assertEquals(0L, window.anchorEpochSecond % (3 * HOUR))
        assertTrue(window.anchorEpochSecond <= NOW.epochSecond)
        assertTrue(NOW.epochSecond - window.anchorEpochSecond < 3 * HOUR)
    }

    @Test
    fun `daily step anchors on local noon`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        val anchor = Instant.ofEpochSecond(window.anchorEpochSecond).atZone(ZONE)
        assertEquals(12, anchor.hour)
        assertEquals(0, anchor.minute)
        assertEquals(NOW.atZone(ZONE).toLocalDate(), anchor.toLocalDate())
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
    fun `history and forecast meet at the anchor`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        assertEquals(ChartWindow.ANCHOR_INDEX, window.historyIndices.last)
        assertEquals(ChartWindow.ANCHOR_INDEX, window.forecastIndices.first)
        assertEquals(ChartWindow.POINT_INDICES.first, window.historyIndices.first)
        assertEquals(ChartWindow.POINT_INDICES.last, window.forecastIndices.last)
    }

    @Test
    fun `x is the anchor index at the anchor and fractional between points`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val anchor = Instant.ofEpochSecond(window.anchorEpochSecond)

        assertEquals(3f, window.xOf(anchor), 0.0001f)
        assertEquals(4.5f, window.xOf(anchor.plusSeconds(4 * HOUR + 1800)), 0.0001f)
        assertEquals(0f, window.xOf(anchor.minusSeconds(9 * HOUR)), 0.0001f)
    }

    @Test
    fun `x runs past the last point for an event beyond the window`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val beyond = Instant.ofEpochSecond(window.anchorEpochSecond + 24 * HOUR)

        // Clipping is the drawing code's job, so the mapping itself must not clamp.
        assertTrue(window.xOf(beyond) > ChartWindow.POINT_INDICES.last)
    }

    @Test
    fun `an event inside the window is covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val anchor = Instant.ofEpochSecond(window.anchorEpochSecond)

        assertTrue(window.covers(alert(anchor.plusSeconds(HOUR), anchor.plusSeconds(5 * HOUR))))
    }

    @Test
    fun `an event overlapping an edge is covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val overLeft = alert(window.firstVisible.minusSeconds(48 * HOUR), window.firstVisible.plusSeconds(HOUR))
        val overRight = alert(window.lastVisible.minusSeconds(HOUR), window.lastVisible.plusSeconds(48 * HOUR))
        val spanning = alert(window.firstVisible.minusSeconds(HOUR), window.lastVisible.plusSeconds(HOUR))

        assertTrue(window.covers(overLeft))
        assertTrue(window.covers(overRight))
        assertTrue(window.covers(spanning))
    }

    @Test
    fun `an event outside the window is not covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val before = alert(window.firstVisible.minusSeconds(10 * HOUR), window.firstVisible.minusSeconds(HOUR))
        val after = alert(window.lastVisible.plusSeconds(HOUR), window.lastVisible.plusSeconds(10 * HOUR))

        assertFalse(window.covers(before))
        assertFalse(window.covers(after))
    }

    @Test
    fun `an event that only touches an edge is not covered`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val endsAtStart = alert(window.firstVisible.minusSeconds(HOUR), window.firstVisible)
        val startsAtEnd = alert(window.lastVisible, window.lastVisible.plusSeconds(HOUR))

        // Either would draw a band of no width, which reads as shading that failed.
        assertFalse(window.covers(endsAtStart))
        assertFalse(window.covers(startsAtEnd))
    }

    @Test
    fun `the daily window reaches half a day past its outer points`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.first) - 12 * HOUR,
            window.firstVisible.epochSecond
        )
        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.last) + 12 * HOUR,
            window.lastVisible.epochSecond
        )
    }

    @Test
    fun `an hourly window stops at its outer points`() {
        val window = ChartWindow.around(NOW, ChartStep.SixHours, ZONE)

        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.first),
            window.firstVisible.epochSecond
        )
        assertEquals(
            window.epochSecondAt(ChartWindow.POINT_INDICES.last),
            window.lastVisible.epochSecond
        )
    }

    @Test
    fun `a wider range reaches an event a narrower one cannot`() {
        val hours24 = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val days7 = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        // Two days ahead: past the 24 hrs chip's 12 hours of forecast, well inside the 7 days chip.
        val event = alert(NOW.plusSeconds(48 * HOUR), NOW.plusSeconds(54 * HOUR))

        assertFalse(hours24.covers(event))
        assertTrue(days7.covers(event))
    }
}
