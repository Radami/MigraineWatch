package com.radami.migrainewatch.ui.components

import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import com.patrykandpatrick.vico.core.entry.FloatEntry
import kotlin.math.abs

/** One reading in a step is a value, not a range: it takes two for the step to span anything. */
private const val MIN_READINGS_FOR_RANGE = 2

/** And two steps that span something, before a band is a band rather than a lone upright. */
private const val MIN_STEPS_FOR_BAND = 2

// What the chart works out before anything is drawn. Kept apart from drawing: every function
// here is pure and covered by PressureChartTest.

/**
 * [items] split wherever their indices stop running consecutively, so a gap in the data
 * breaks the lines rather than being bridged. Generic so it can be tested without a draw context.
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
 * How the chart draws the readings each of its points stands for. Independent of [ChartStep]
 * (which decides how much time a point covers). A caller requests one per range, but
 * [renderingFor] can fall back to [Line] when a step has too little data for a range.
 */
enum class ChartRendering {

    /** A single line through the pressure sampled at each point. */
    Line,

    /** Lowest and highest pressure within each point's step, as two lines with a wash
     * between them, showing how far pressure moved during the step. */
    MinMaxBand
}

/**
 * What the chart plots at x = [index]: the step's low and high, or for [ChartRendering.Line]
 * the one sampled value given as both, so a line can be treated as a degenerate band.
 */
internal data class RangeEntry(val index: Int, val minY: Float, val maxY: Float)

/**
 * The two edges the chart plots, in chart x-order. Always a pair, even for a line (whose
 * edges coincide), so Vico can tween the same model shape across renderings.
 */
internal data class SeriesEdges(val lower: List<FloatEntry>, val upper: List<FloatEntry>)

/**
 * Pressure at exactly [epoch], interpolated between the surrounding readings ([readings] must
 * be sorted by time). Null outside the data range, so a gap drops the point rather than
 * reusing a reading from elsewhere.
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
 * Lowest and highest pressure within each of the window's steps, using readings within half
 * a step either side so the range centres on its labelled point. Steps with fewer than two
 * readings are dropped. Empty for [ChartRendering.Line], which has no use for it.
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
        if (inStep.size < MIN_READINGS_FOR_RANGE) return@mapNotNull null
        RangeEntry(i, inStep.minOf { it.pressureMsl }, inStep.maxOf { it.pressureMsl })
    }
}

/**
 * What the chart can actually draw, which may not be what was requested: a band needs at
 * least two ranged steps or it falls back to the line. Resolved once so marks, line colour
 * and legend all agree on what is shown.
 */
internal fun renderingFor(
    requested: ChartRendering,
    stepRanges: List<RangeEntry>,
): ChartRendering =
    if (stepRanges.size >= MIN_STEPS_FOR_BAND) requested else ChartRendering.Line

/**
 * The two edges to plot, for whichever [rendering] was settled on. A line is built as a pair
 * too, so switching rendering moves the edges apart or together instead of swapping drawings.
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
 * `edgeX`, in hPa. An offset (not an absolute value) so the overhang stays hinged to the
 * point and rides Vico's tween with it. A band returns no offset: its edges are one step's
 * extremes and don't extend past it.
 */
internal fun edgeOffsetSampler(
    readings: List<PressureReading>,
    window: ChartWindow,
    rendering: ChartRendering,
): (Float, Int) -> Float? = when (rendering) {
    ChartRendering.Line -> { edgeX, endIndex ->
        val atEdge = pressureAt(readings, window.instantAt(edgeX).epochSecond)
        val atEnd = pressureAt(readings, window.epochSecondAt(endIndex))

        // Null unless both ends of the strip have data, matching how gaps break the lines
        // elsewhere.
        if (atEdge == null || atEnd == null) null else atEdge - atEnd
    }

    ChartRendering.MinMaxBand -> { _, _ -> 0f }
}
