package com.radami.migrainewatch.ui.components

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import com.patrykandpatrick.vico.core.entry.FloatEntry
import kotlin.math.abs

// What the chart works out before anything is drawn. Kept apart from the drawing because none of
// it needs a canvas: every function here is pure and covered by PressureChartTest.

/**
 * [items] split wherever their indices stop running consecutively.
 *
 * A gap in the data has to break the lines where it falls rather than be bridged by a segment
 * describing no step at all: the chart drops a point it has no readings for, so a hole in the
 * series arrives here as a jump in the indices and nothing else.
 *
 * Generic over what it is splitting so it can be exercised without a draw context — the points
 * it runs on in the chart only exist part-way through a frame.
 */
internal fun <T> consecutiveRuns(items: List<T>, indexOf: (T) -> Int): List<List<T>> = buildList {
    var current = mutableListOf<T>()
    for (item in items) {
        if (current.isEmpty() || indexOf(item) == indexOf(current.last()) + 1) {
            current.add(item)
        } else {
            add(current)
            current = mutableListOf(item)
        }
    }
    if (current.isNotEmpty()) add(current)
}

/**
 * How the chart draws the readings each of its points stands for.
 *
 * Separate from [ChartStep] because the two are independent: a step decides how much time a
 * point covers, this decides what is drawn for it. They were one thing while only the daily
 * step drew a band, which is what made a band at any other step impossible to ask for.
 *
 * A caller picks one per range, and the chart falls back to [Line] regardless when a step
 * holds too little data to have a range at all — see `drawn` in [PressureChart], the single
 * value the marks, the line colour and the legend all key off.
 */
enum class ChartRendering {

    /** A single line through the pressure sampled at each point. */
    Line,

    /**
     * The lowest and highest pressure within each point's step, as two lines with a wash
     * between them. Says how far pressure moved inside a step rather than where it happened
     * to be at the instant the step was sampled.
     */
    MinMaxBand
}

/**
 * What the chart plots at x = [index]: the lowest and highest pressure within that step, or
 * for a [ChartRendering.Line] the one sampled pressure given as both. Carrying the pair
 * whichever is drawn lets the overlays treat a line as the band whose edges coincide, the
 * same equivalence [SeriesEdges] rests on.
 */
internal data class RangeEntry(val index: Int, val minY: Float, val maxY: Float)

/**
 * The two edges the chart plots, in chart x-order.
 *
 * Both renderings produce a pair, because a pair is what lets one turn into the other: a line
 * is the degenerate band whose edges coincide. Vico tweens a model into the next one, so
 * keeping the shape of the model the same across renderings is what makes switching range a
 * movement rather than a swap.
 */
internal data class SeriesEdges(val lower: List<FloatEntry>, val upper: List<FloatEntry>)

/**
 * Pressure at exactly [epoch], linearly interpolated between the two surrounding readings
 * ([readings] must be sorted by time). Returns null outside the data range, so a missing
 * stretch of data drops the chart point instead of silently reusing a reading from a
 * different time.
 */
internal fun pressureAt(readings: List<PressureReading>, epoch: Long): Float? {
    val after = readings.firstOrNull { it.dateTime.epochSecond >= epoch } ?: return null
    if (after.dateTime.epochSecond == epoch) return after.pressureMsl
    val before = readings.lastOrNull { it.dateTime.epochSecond <= epoch } ?: return null
    val t0 = before.dateTime.epochSecond
    val t1 = after.dateTime.epochSecond
    val fraction = (epoch - t0).toFloat() / (t1 - t0)
    return before.pressureMsl + fraction * (after.pressureMsl - before.pressureMsl)
}

/**
 * The lowest and highest pressure within each of the window's steps.
 *
 * A step's readings are the ones within half a step either side of the instant it is sampled
 * at, so the range is centred on the point its label names rather than trailing behind it. A
 * step holding fewer than two readings is left out: one reading is a value, not a range.
 *
 * Empty for a [ChartRendering.Line], which has no use for it.
 */
internal fun stepRanges(
    readings: List<PressureReading>,
    window: ChartWindow,
    rendering: ChartRendering,
): List<RangeEntry> {
    if (rendering != ChartRendering.MinMaxBand) return emptyList()

    val half = window.step.seconds / 2
    return ChartWindow.POINT_INDICES.mapNotNull { i ->
        val anchorEpoch = window.epochSecondAt(i)
        val inStep = readings.filter { abs(it.dateTime.epochSecond - anchorEpoch) <= half }
        if (inStep.size < 2) return@mapNotNull null
        RangeEntry(i, inStep.minOf { it.pressureMsl }, inStep.maxOf { it.pressureMsl })
    }
}

/**
 * What the chart can actually draw, which is not always what the caller asked for.
 *
 * A band needs at least two steps with a range to be a band at all; a series too sparse for
 * that would leave the plot empty, so it falls back to the line. Resolved before the marks are
 * built, so the marks, the line colour and the legend all agree on which of the two is on
 * screen.
 */
internal fun renderingFor(
    requested: ChartRendering,
    stepRanges: List<RangeEntry>,
): ChartRendering = if (stepRanges.size >= 2) requested else ChartRendering.Line

/**
 * The two edges to plot, for whichever rendering [rendering] settled on.
 *
 * A line is the band whose edges coincide, so it is built as a pair too rather than as one
 * series: switching range then moves the edges apart or together instead of swapping one
 * drawing for another, which is the whole reason the transition animates.
 */
internal fun seriesEdges(
    readings: List<PressureReading>,
    window: ChartWindow,
    rendering: ChartRendering,
    stepRanges: List<RangeEntry>,
): SeriesEdges = when (rendering) {
    ChartRendering.Line -> {
        val sampled = ChartWindow.POINT_INDICES.mapNotNull { i ->
            pressureAt(readings, window.epochSecondAt(i))?.let { FloatEntry(i.toFloat(), it) }
        }
        SeriesEdges(lower = sampled, upper = sampled)
    }

    ChartRendering.MinMaxBand -> SeriesEdges(
        lower = stepRanges.map { FloatEntry(it.index.toFloat(), it.minY) },
        upper = stepRanges.map { FloatEntry(it.index.toFloat(), it.maxY) }
    )
}

/**
 * How far the series moves from the point at `endIndex` out to the plot edge at chart x
 * `edgeX`, in hPa.
 *
 * An offset rather than a value, so the overhang is hinged on wherever that point currently
 * is and rides Vico's tween with it. An absolute value would be the one thing on the chart
 * standing still while everything around it moved.
 *
 * A line is sampled out there like anywhere else: the strip is real time — an hourly plot
 * reserves it so its extreme labels clear the axis — and [readings] covers it. A band does not
 * move at all: its edges are one step's extremes, which hold across the whole of that step's
 * cell, so following the curve out would draw a range no step actually had.
 */
internal fun edgeOffsetSampler(
    readings: List<PressureReading>,
    window: ChartWindow,
    rendering: ChartRendering,
): (Float, Int) -> Float? = when (rendering) {
    ChartRendering.Line -> { edgeX, endIndex ->
        val atEdge = pressureAt(readings, window.instantAt(edgeX).epochSecond)
        val atEnd = pressureAt(readings, window.epochSecondAt(endIndex))

        // Nothing to carry unless both ends of the strip are covered; a gap at the plot edge
        // is the truth, the same one that breaks the lines where readings are missing.
        if (atEdge == null || atEnd == null) null else atEdge - atEnd
    }

    ChartRendering.MinMaxBand -> { _, _ -> 0f }
}
