package com.radami.migrainewatch.ui.components

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What the chart works out before it draws anything: which steps hold a range, which rendering
 * that leaves it able to draw, the series it plots, and how far it carries that series past its
 * last point to reach the edge of the plot.
 *
 * The drawing itself is not covered here — it needs a Vico draw context and a canvas — so these
 * cover the decisions rather than the pixels.
 */
class PressureChartTest {

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")

        /** 14:37 local, so nothing lands on a step boundary by accident. */
        val NOW: Instant = ZonedDateTime.of(2026, 8, 22, 14, 37, 0, 0, ZONE).toInstant()

        const val HOUR = 3600L
        const val EPSILON = 0.001f

        fun reading(at: Instant, pressure: Float) = PressureReading(
            dateTime = at,
            pressureMsl = pressure,
            surfacePressure = pressure - 10f,
            fetchedDateTime = NOW
        )

        /**
         * Hourly readings spanning the whole of [window] and an hour past each end, so nothing
         * a test asks for falls outside the data unless it means to. The pressure at any hour
         * is that hour's offset from the window's anchor, which makes an expected value
         * something a test can state rather than look up.
         */
        fun coveringReadings(window: ChartWindow): List<PressureReading> {
            val first = window.epochSecondAt(ChartWindow.POINT_INDICES.first) - HOUR
            val last = window.epochSecondAt(ChartWindow.POINT_INDICES.last) + HOUR
            return generateSequence(first) { it + HOUR }
                .takeWhile { it <= last }
                .map { epoch ->
                    val hoursFromAnchor = (epoch - window.anchorEpochSecond) / HOUR
                    reading(Instant.ofEpochSecond(epoch), 1000f + hoursFromAnchor)
                }
                .toList()
        }
    }

    // --- pressureAt -----------------------------------------------------------------------

    @Test
    fun `a reading at exactly the asked-for instant is used as it stands`() {
        val at = Instant.ofEpochSecond(1_000_000)
        val readings = listOf(reading(at, 1013.25f))

        assertEquals(1013.25f, pressureAt(readings, at.epochSecond)!!, EPSILON)
    }

    @Test
    fun `pressure between two readings is interpolated`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val readings = listOf(
            reading(start, 1000f),
            reading(start.plusSeconds(HOUR), 1004f)
        )

        // A quarter of the way across the gap is a quarter of the way up the rise.
        assertEquals(1001f, pressureAt(readings, start.epochSecond + HOUR / 4)!!, EPSILON)
        assertEquals(1002f, pressureAt(readings, start.epochSecond + HOUR / 2)!!, EPSILON)
    }

    @Test
    fun `pressure outside the readings is unknown rather than the nearest one`() {
        val start = Instant.ofEpochSecond(1_000_000)
        val readings = listOf(
            reading(start, 1000f),
            reading(start.plusSeconds(HOUR), 1004f)
        )

        assertNull(pressureAt(readings, start.epochSecond - 1))
        assertNull(pressureAt(readings, start.epochSecond + HOUR + 1))
    }

    // --- stepRanges -----------------------------------------------------------------------

    @Test
    fun `a step's range covers the half-step either side of its own instant`() {
        val window = ChartWindow.around(NOW, ChartStep.SixHours, ZONE)
        val anchor = window.anchorEpochSecond

        // Two readings three hours either side of the anchor, and one just outside the step.
        val readings = listOf(
            reading(Instant.ofEpochSecond(anchor - 3 * HOUR), 1001f),
            reading(Instant.ofEpochSecond(anchor + 3 * HOUR), 1009f),
            reading(Instant.ofEpochSecond(anchor + 4 * HOUR), 1030f)
        )

        val ranges = stepRanges(readings, window, ChartRendering.MinMaxBand)
        val atAnchor = ranges.single { it.index == ChartWindow.ANCHOR_INDEX }

        assertEquals(1001f, atAnchor.minY, EPSILON)
        assertEquals(1009f, atAnchor.maxY, EPSILON)
    }

    @Test
    fun `a step holding one reading has no range and is left out`() {
        val window = ChartWindow.around(NOW, ChartStep.SixHours, ZONE)
        val readings = listOf(reading(Instant.ofEpochSecond(window.anchorEpochSecond), 1013f))

        assertEquals(emptyList<RangeEntry>(), stepRanges(readings, window, ChartRendering.MinMaxBand))
    }

    @Test
    fun `a line asks for no ranges at all`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        assertEquals(
            emptyList<RangeEntry>(),
            stepRanges(coveringReadings(window), window, ChartRendering.Line)
        )
    }

    @Test
    fun `every point of a fully covered window has a range`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)

        val ranges = stepRanges(coveringReadings(window), window, ChartRendering.MinMaxBand)

        assertEquals(ChartWindow.POINT_INDICES.toList(), ranges.map { it.index })
    }

    // --- renderingFor ---------------------------------------------------------------------

    @Test
    fun `a band with fewer than two ranges falls back to a line`() {
        val oneRange = listOf(RangeEntry(index = 0, minY = 1000f, maxY = 1005f))

        assertEquals(ChartRendering.Line, renderingFor(ChartRendering.MinMaxBand, emptyList()))
        assertEquals(ChartRendering.Line, renderingFor(ChartRendering.MinMaxBand, oneRange))
    }

    @Test
    fun `a band with two ranges is drawn as asked`() {
        val ranges = listOf(
            RangeEntry(index = 0, minY = 1000f, maxY = 1005f),
            RangeEntry(index = 1, minY = 1002f, maxY = 1007f)
        )

        assertEquals(ChartRendering.MinMaxBand, renderingFor(ChartRendering.MinMaxBand, ranges))
    }

    @Test
    fun `a line stays a line however much data there is`() {
        val ranges = listOf(
            RangeEntry(index = 0, minY = 1000f, maxY = 1005f),
            RangeEntry(index = 1, minY = 1002f, maxY = 1007f)
        )

        assertEquals(ChartRendering.Line, renderingFor(ChartRendering.Line, ranges))
    }

    // --- seriesEdges ----------------------------------------------------------------------

    @Test
    fun `a line is the band whose edges coincide`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        val edges = seriesEdges(coveringReadings(window), window, ChartRendering.Line, emptyList())

        assertEquals(edges.lower, edges.upper)
        assertEquals(ChartWindow.POINT_INDICES.map { it.toFloat() }, edges.lower.map { it.x })
    }

    @Test
    fun `a band's edges are the ranges it was given`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)
        val ranges = listOf(
            RangeEntry(index = 2, minY = 1000f, maxY = 1005f),
            RangeEntry(index = 3, minY = 1002f, maxY = 1007f)
        )

        val edges = seriesEdges(emptyList(), window, ChartRendering.MinMaxBand, ranges)

        assertEquals(listOf(2f, 3f), edges.lower.map { it.x })
        assertEquals(listOf(1000f, 1002f), edges.lower.map { it.y })
        assertEquals(listOf(1005f, 1007f), edges.upper.map { it.y })
    }

    @Test
    fun `a point with no reading is dropped rather than plotted at zero`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)

        // Only the anchor and the step after it are covered, so only those two can be plotted.
        val readings = listOf(
            reading(Instant.ofEpochSecond(window.epochSecondAt(ChartWindow.ANCHOR_INDEX)), 1010f),
            reading(Instant.ofEpochSecond(window.epochSecondAt(ChartWindow.ANCHOR_INDEX + 1)), 1012f)
        )

        val edges = seriesEdges(readings, window, ChartRendering.Line, emptyList())

        assertEquals(
            listOf(ChartWindow.ANCHOR_INDEX.toFloat(), ChartWindow.ANCHOR_INDEX + 1f),
            edges.lower.map { it.x }
        )
    }

    // --- edgeOffsetSampler ----------------------------------------------------------------

    @Test
    fun `a line is sampled out to the plot edge`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val readings = coveringReadings(window)
        val lastIndex = ChartWindow.POINT_INDICES.last

        val offsetAt = edgeOffsetSampler(readings, window, ChartRendering.Line)

        // A third of a step past the last point, on readings that rise 1 hPa an hour over a
        // three-hour step: one hour further along, so one hPa higher.
        val offset = offsetAt(lastIndex + 1 / 3f, lastIndex)

        assertNotNull(offset)
        assertEquals(1f, offset!!, EPSILON)
    }

    @Test
    fun `a line falling towards the edge carries a negative offset`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val readings = coveringReadings(window).map { it.copy(pressureMsl = -it.pressureMsl) }
        val firstIndex = ChartWindow.POINT_INDICES.first

        val offsetAt = edgeOffsetSampler(readings, window, ChartRendering.Line)
        val offset = offsetAt(firstIndex - 1 / 3f, firstIndex)

        assertNotNull(offset)
        assertEquals(1f, offset!!, EPSILON)
    }

    @Test
    fun `a line stops at the edge of its readings rather than inventing a value`() {
        val window = ChartWindow.around(NOW, ChartStep.ThreeHours, ZONE)
        val lastIndex = ChartWindow.POINT_INDICES.last

        // Readings that stop exactly on the last point, so the strip beyond it is uncovered.
        val readings = ChartWindow.POINT_INDICES.map { i ->
            reading(Instant.ofEpochSecond(window.epochSecondAt(i)), 1000f + i)
        }

        val offsetAt = edgeOffsetSampler(readings, window, ChartRendering.Line)

        assertNull(offsetAt(lastIndex + 0.5f, lastIndex))
    }

    @Test
    fun `a band holds its step's range flat out to the edge`() {
        val window = ChartWindow.around(NOW, ChartStep.OneDay, ZONE)
        val readings = coveringReadings(window)

        val offsetAt = edgeOffsetSampler(readings, window, ChartRendering.MinMaxBand)

        // Whatever the readings do out there, a day's extremes hold across the whole of its
        // cell, so the band leaves its last point level.
        assertEquals(0f, offsetAt(ChartWindow.POINT_INDICES.last + 0.5f, 7)!!, EPSILON)
        assertEquals(0f, offsetAt(ChartWindow.POINT_INDICES.first - 0.5f, 0)!!, EPSILON)
    }
}
