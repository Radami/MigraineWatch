package com.radami.migrainewatch.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.domain.AlertWindow
import com.radami.migrainewatch.domain.ChartStep
import com.radami.migrainewatch.domain.ChartWindow
import com.radami.migrainewatch.format.AppDateFormats
import com.radami.migrainewatch.ui.theme.ChartNowLineDark
import com.radami.migrainewatch.ui.theme.ChartNowLineLight
import com.radami.migrainewatch.ui.theme.ChartSeriesDark
import com.radami.migrainewatch.ui.theme.ChartSeriesLight
import com.radami.migrainewatch.ui.theme.alertColorPalette
import com.patrykandpatrick.vico.core.Animation
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.style.currentChartStyle
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.DefaultPointConnector
import com.patrykandpatrick.vico.core.chart.decoration.Decoration
import com.patrykandpatrick.vico.core.chart.draw.ChartDrawContext
import com.patrykandpatrick.vico.core.chart.layout.HorizontalLayout
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.ChartEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// Every overlay is a wash over the plot rather than a fill: the data has to stay readable
// through all of them, including where a risk window and the daily range overlap.
private const val ALERT_BAND_ALPHA = 0.15f
private const val RANGE_BAND_ALPHA = 0.2f
private const val NOW_LINE_ALPHA = 0.5f

/** Stroke width of the min and max lines, in dp. */
private const val RANGE_LINE_WIDTH_DP = 2f

/**
 * How the risk shading keeps step with a chart that is still moving.
 *
 * Everything the decoration draws from the series — the wash, the lone-step stroke, the
 * overhang — travels with the edges frame by frame, off the positions [TweeningLineChart]
 * catches as Vico draws with them. The risk shading cannot. Its windows are instants mapped
 * through the new range, so they arrive at their new width and position in a single frame,
 * under a chart that has not finished moving.
 *
 * So the shading stands aside instead of lying: it drops out as the range changes and returns
 * over the tail of the tween, reaching full strength as the edges settle. The fade
 * deliberately overlaps the tween — the model still animating is what keeps the chart being
 * redrawn, and a fade starting after it ended might never render.
 */
private const val RISK_FADE_DELAY_MILLIS = Animation.DIFF_DURATION / 2
private const val RISK_FADE_MILLIS = Animation.DIFF_DURATION - RISK_FADE_DELAY_MILLIS

/** Whether a traced run opens a new path contour or continues the one in progress. */
private enum class RunStart { MoveTo, LineTo }

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

private val SWATCH_WIDTH = 24.dp

/** Risk gets one swatch per window in view, so they are narrowed to leave the legend on one line. */
private val RISK_SWATCH_WIDTH = 14.dp

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
 * A point of the chart as it is on screen: its x index, and its two edges in pixels.
 *
 * The pixel twin of [RangeEntry] — one says what the chart plots, the other where that has
 * landed this frame, which mid-tween is not the same thing.
 */
private data class DrawnEntry(val index: Int, val minPx: Float, val maxPx: Float)

/** One alert's risk window, in chart x-values, in the colour of the row describing it. */
private data class AlertBand(val startX: Float, val endX: Float, val color: Color)

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

/**
 * Where the chart's points sit this frame, as the fraction of the plot height each is drawn
 * at, by series and then by entry x. Empty when nothing is animating.
 *
 * Written by [TweeningLineChart] and read by [ChartOverlayDecoration], which have no other way
 * to reach each other: a chart is handed the model being interpolated, a decoration is not.
 */
private class DrawnPositions {
    var byEntryX: List<Map<Float, Float>> = emptyList()
}

/**
 * A [LineChart] that publishes where it is drawing its points.
 *
 * Vico tweens a range change by writing interpolated positions into the model's extra store.
 * They reach [drawChart] but never a [Decoration]: the chart values a decoration can read are
 * built once, from the settled model, and handed to every frame of the animation unchanged. So
 * the wash and the overhang could only ever draw the destination — which is why they used to
 * stand aside until the edges had finished travelling rather than lie about where they were.
 *
 * Catching the positions on their way through lets them travel with the edges instead. What is
 * caught is the very thing [LineChart] draws with, so the two cannot disagree.
 */
private class TweeningLineChart(private val positions: DrawnPositions) : LineChart() {

    override fun drawChartInternal(context: ChartDrawContext, model: ChartEntryModel) {
        positions.byEntryX = model.extraStore.getOrNull(drawingModelKey)
            .orEmpty()
            .map { series -> series.mapValues { (_, point) -> point.y } }

        // Decorations are drawn from inside here, so they see this frame and not the last one.
        super.drawChartInternal(context, model)
    }
}

/**
 * Draws the alert risk bands (behind the chart line) and the daily min/max range and the
 * "now" dashed line (above it) using Vico's Decoration API, which provides exact chart
 * data-area bounds.
 */
private class ChartOverlayDecoration(
    private val alertBands: List<AlertBand>,
    private val rendering: ChartRendering,
    /**
     * How far the series moves from a given point out to the plot edge, or null where there is
     * nothing to draw from — see [edgeOffsetSampler].
     */
    private val edgeOffsetAt: (Float, Int) -> Float?,
    private val positions: DrawnPositions,
    private val seriesColor: Color,
    /**
     * How far in the risk shading is, read at each draw rather than fixed when the decoration
     * is built. Nothing else fades; see [RISK_FADE_DELAY_MILLIS].
     *
     * A value here would make the fade a property of the decoration, and the decoration would
     * have to be rebuilt — five paints, a connector and a fresh `setDecorations` — for every
     * frame of it. As a read it costs nothing, and it lands in the draw phase: the fade runs
     * over the model's own tween, which is redrawing the chart every frame regardless.
     */
    private val riskAlpha: () -> Float,
    private val nowX: Float,
    private val nowLineColorArgb: Int,
) : Decoration {

    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = seriesColor.copy(alpha = RANGE_BAND_ALPHA).toArgb()
    }

    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = seriesColor.toArgb()
    }

    // The overhang continues a line Vico has already drawn, so it is butt-capped at both ends:
    // a round cap would bulge half a stroke past the plot edge and pool over the join.
    private val overhangPaint = Paint(rangePaint).apply {
        strokeCap = Paint.Cap.BUTT
    }

    // The very connector Vico's own line spec uses, so the min/max lines curve exactly like
    // the pressure line a Line rendering draws.
    private val pointConnector = DefaultPointConnector()

    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = nowLineColorArgb
    }

    // Density-dependent stroke properties are initialised lazily on first draw.
    private var initialisedDensity = 0f

    private fun ensurePaintDensity(density: Float) {
        if (initialisedDensity == density) return
        initialisedDensity = density
        nowPaint.strokeWidth = 2f * density
        nowPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
        rangePaint.strokeWidth = RANGE_LINE_WIDTH_DP * density
        overhangPaint.strokeWidth = RANGE_LINE_WIDTH_DP * density
    }

    // Maps a chart x-value to a pixel position via Vico's horizontal dimensions — the same
    // formula Vico uses to place line points — so the overlays stay aligned with the data
    // in both FullWidth (hourly) and Segmented (daily) layouts.
    private fun ChartDrawContext.dataX(x: Float, bounds: RectF): Float {
        val chartValues = chartValuesProvider.getChartValues()
        return bounds.left + horizontalDimensions.startPadding +
            (x - chartValues.minX) / chartValues.xStep * horizontalDimensions.xSpacing -
            horizontalScroll
    }

    /** [dataX] read backwards: which chart x-value the pixel column [px] stands for. */
    private fun ChartDrawContext.dataXInverse(px: Float, bounds: RectF): Float {
        val chartValues = chartValuesProvider.getChartValues()
        return chartValues.minX +
            (px - bounds.left - horizontalDimensions.startPadding + horizontalScroll) /
            horizontalDimensions.xSpacing * chartValues.xStep
    }

    /**
     * The series as it is on screen this frame, already in pixels.
     *
     * Mid-tween that is where [TweeningLineChart] caught the edges travelling; once they have
     * settled no positions are being written and each entry's own value places it. The
     * arithmetic is `LineChart.forEachPointWithinBoundsIndexed`'s, so a point of the wash and
     * the point of the line it belongs to land on the same pixel.
     *
     * The two series are the band's edges, and a line is the band whose edges coincide, so one
     * shape covers both renderings.
     */
    private fun ChartDrawContext.drawnSeries(bounds: RectF): List<DrawnEntry> {
        val chartValues = chartValuesProvider.getChartValues()
        val entries = chartValues.chartEntryModel.entries

        fun pixelY(seriesIndex: Int, entry: ChartEntry): Float {
            val fraction = positions.byEntryX.getOrNull(seriesIndex)?.get(entry.x)
                ?: ((entry.y - chartValues.minY) / chartValues.lengthY)
            return bounds.bottom - fraction * bounds.height()
        }

        val lower = entries.getOrNull(0).orEmpty()
        val upper = entries.getOrNull(1).orEmpty()
        return lower.zip(upper) { low, high ->
            DrawnEntry(low.x.roundToInt(), minPx = pixelY(0, low), maxPx = pixelY(1, high))
        }
    }

    // Group consecutive entries so a gap in the data breaks the lines there rather than
    // bridging it with a segment that describes no day.
    private fun runsOf(series: List<DrawnEntry>): List<List<DrawnEntry>> = buildList {
        var current = mutableListOf<DrawnEntry>()
        for (entry in series) {
            if (current.isEmpty() || entry.index == current.last().index + 1) {
                current.add(entry)
            } else {
                add(current)
                current = mutableListOf(entry)
            }
        }
        if (current.isNotEmpty()) add(current)
    }

    override fun onDrawBehindChart(context: ChartDrawContext, bounds: RectF) {
        ensurePaintDensity(context.density)
        drawAlertBands(context, bounds)

        // Read once, so everything below describes the same frame of the same tween.
        val series = context.drawnSeries(bounds)

        // Under the edges Vico draws, and over the risk shading: the wash is the area those
        // edges enclose, so it has to sit between the two.
        drawRangeBand(context, bounds, series)
        drawOverhangs(context, bounds, series)
    }

    private fun drawAlertBands(context: ChartDrawContext, bounds: RectF) {
        val alpha = riskAlpha()
        alertBands.forEach { band ->
            // An event can begin before the window or run past its end. Clipping to the plot
            // area shows the part that is in view; the rest is accounted for by the list
            // beside the chart, which marks what the current range cannot reach.
            val left = maxOf(context.dataX(band.startX, bounds), bounds.left)
            val right = minOf(context.dataX(band.endX, bounds), bounds.right)
            if (right <= left) return@forEach

            // Recomputed per band per frame rather than held: three colours is nothing beside
            // rebuilding the decoration, which is what caching them across a fade would cost.
            alertPaint.color = band.color.copy(alpha = ALERT_BAND_ALPHA * alpha).toArgb()
            context.canvas.drawRect(left, bounds.top, right, bounds.bottom, alertPaint)
        }
    }

    private fun drawRangeBand(context: ChartDrawContext, bounds: RectF, series: List<DrawnEntry>) {
        if (rendering != ChartRendering.MinMaxBand || series.isEmpty()) return

        val path = Path()
        for (run in runsOf(series)) {
            // A run of one has no neighbour to trace towards, so there is no area to fill:
            // the step is drawn as the vertical it spans instead of vanishing.
            if (run.size == 1) {
                drawIsolatedStep(context, bounds, run.first())
                continue
            }

            // Only the fill: the edges themselves are model series, drawn by Vico on top of
            // this, which is what lets them tween when the range changes.
            drawBand(context, bounds, path, run)
        }
    }

    // Down the max edge, back along the min edge: the two verticals the traversal closes over
    // are the ends of the run, so the fill follows the same curves the strokes do.
    private fun drawBand(
        context: ChartDrawContext,
        bounds: RectF,
        path: Path,
        run: List<DrawnEntry>,
    ) {
        path.reset()
        traceRun(context, bounds, path, run, RunStart.MoveTo) { it.maxPx }
        traceRun(context, bounds, path, run.asReversed(), RunStart.LineTo) { it.minPx }
        path.close()
        context.canvas.drawPath(path, bandPaint)
    }

    private fun traceRun(
        context: ChartDrawContext,
        bounds: RectF,
        path: Path,
        run: List<DrawnEntry>,
        start: RunStart,
        pixelYOf: (DrawnEntry) -> Float,
    ) {
        var prevX = 0f
        var prevY = 0f
        run.forEachIndexed { i, entry ->
            val x = context.dataX(entry.index.toFloat(), bounds)
            val y = pixelYOf(entry)
            when {
                i > 0 -> pointConnector
                    .connect(path, prevX, prevY, x, y, context.horizontalDimensions, bounds)
                start == RunStart.MoveTo -> path.moveTo(x, y)
                else -> path.lineTo(x, y)
            }
            prevX = x
            prevY = y
        }
    }

    /**
     * Carries the series out to the left and right plot edges.
     *
     * The points stop short of them for reasons that have nothing to do with the data: an
     * hourly plot reserves room so its first and last labels clear the axis, and a daily one
     * gives each day a cell and puts the point at the centre. Left alone, both leave a strip
     * of empty plot under risk shading that does reach the edge, which reads as missing data.
     *
     * Drawn here rather than added to the model because neither strip is a point the axis
     * places — they are what is left over once it has — and an entry out there would be given
     * a cell and a label of its own.
     */
    private fun drawOverhangs(context: ChartDrawContext, bounds: RectF, series: List<DrawnEntry>) {
        val first = series.firstOrNull() ?: return
        drawOverhang(context, bounds, first, bounds.left)
        drawOverhang(context, bounds, series.last(), bounds.right)
    }

    private fun drawOverhang(
        context: ChartDrawContext,
        bounds: RectF,
        from: DrawnEntry,
        edgePx: Float,
    ) {
        val fromPx = context.dataX(from.index.toFloat(), bounds)

        // Nothing to carry when the point already sits on the edge; a zero-length segment
        // would still lay down a stroke of its own width.
        if (abs(edgePx - fromPx) < 1f) return

        val offset = edgeOffsetAt(context.dataXInverse(edgePx, bounds), from.index) ?: return

        // The offset is in hPa and the point is already in pixels, so only the rise converts.
        // Screen y grows downward, which is why a rise in pressure subtracts.
        val chartValues = context.chartValuesProvider.getChartValues()
        val rise = offset / chartValues.lengthY * bounds.height()
        val fromMax = from.maxPx
        val fromMin = from.minPx
        val toMax = from.maxPx - rise
        val toMin = from.minPx - rise

        // The wash first, so the two edges sit on top of it exactly as they do over the run.
        if (rendering == ChartRendering.MinMaxBand) {
            val path = Path().apply {
                moveTo(fromPx, fromMax)
                lineTo(edgePx, toMax)
                lineTo(edgePx, toMin)
                lineTo(fromPx, fromMin)
                close()
            }
            context.canvas.drawPath(path, bandPaint)
        }

        // Both edges whichever is drawn: a line traces the same segment twice, over itself,
        // the way Vico draws its two coincident series for one.
        context.canvas.drawLine(fromPx, fromMax, edgePx, toMax, overhangPaint)
        context.canvas.drawLine(fromPx, fromMin, edgePx, toMin, overhangPaint)
    }

    private fun drawIsolatedStep(context: ChartDrawContext, bounds: RectF, entry: DrawnEntry) {
        val x = context.dataX(entry.index.toFloat(), bounds)
        context.canvas.drawLine(x, entry.maxPx, x, entry.minPx, rangePaint)
    }

    override fun onDrawAboveChart(context: ChartDrawContext, bounds: RectF) {
        ensurePaintDensity(context.density)

        val x = context.dataX(nowX, bounds)
        context.canvas.drawLine(x, bounds.top, x, bounds.bottom, nowPaint)
    }
}

/**
 * @param readings every reading the chart may draw from, sorted by time. The chart samples the
 *   eight instants [window] names out of these rather than plotting them one for one, so it is
 *   given the whole series and not the slice one range happens to need.
 * @param window which slice of time the chart draws, and at what resolution.
 * @param rendering what to draw for each of its points — a sampled line, or the band each
 *   step's readings span. Independent of the step: any step can be drawn either way.
 * @param alerts risk windows to shade, in the order the caller lists them. Those
 *   [ChartWindow.covers] returns false for are left to the caller to account for — the chart
 *   cannot show them at this range — and only as many as the palette has colours are shaded,
 *   so no two bands on one chart can be the same colour.
 * @param emptyContent what to put in the chart's place when there is nothing to plot. Required,
 *   and a slot rather than a message: the chart is the only thing that knows whether the
 *   readings reach the window it was given, and the screen around it is the only thing that
 *   knows why they might not — so each says the half it can.
 */
@Composable
fun PressureChart(
    readings: List<PressureReading>,
    window: ChartWindow,
    modifier: Modifier = Modifier,
    rendering: ChartRendering = ChartRendering.Line,
    alerts: List<AlertWindow> = emptyList(),
    emptyContent: @Composable () -> Unit
) {
    // Two separate questions that used to have one answer. The step still decides how labels
    // are written and how the axis lays out; only the marks depend on the rendering.
    val isDaily = window.step == ChartStep.OneDay
    val stepSeconds = window.step.seconds

    // Remembered unconditionally rather than inside the branch that needs it: switching
    // rendering would otherwise change the shape of the composition.
    val rangeEntries = remember(readings, window, rendering) {
        stepRanges(readings, window, rendering)
    }

    val drawn = renderingFor(rendering, rangeEntries)

    val edges = remember(readings, window, drawn, rangeEntries) {
        seriesEdges(readings, window, drawn, rangeEntries)
    }

    // Nothing to plot: not one of the eight instants this window names falls inside the
    // readings. An empty table does it, and so does a cache that stopped a day ago with the
    // 24-hour chip selected — the chart cannot reach back that far, though the data is there.
    //
    // The one place this is decided. Drawing every part of the chart from these edges means a
    // series that cannot fill them cannot half-fill them either, and the axes, the overlays and
    // the legend used to vanish together and leave the card blank without saying anything.
    if (edges.lower.isEmpty()) {
        emptyContent()
        return
    }

    val edgeOffsetAt = remember(readings, window, drawn) {
        edgeOffsetSampler(readings, window, drawn)
    }

    // Both edges go through the model, so Vico tweens them between ranges the way it already
    // tweened the sampled line. The decoration reads that same model as it is interpolated, so
    // the wash and the overhang travel with them — see drawnSeries in ChartOverlayDecoration.
    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(edges) {
        modelProducer.setEntries(listOf(edges.lower, edges.upper))
    }

    val isDark = isSystemInDarkTheme()
    // One colour for the data whichever way it is drawn, so changing range changes the shape
    // on screen and nothing else.
    val seriesColor = if (isDark) ChartSeriesDark else ChartSeriesLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight

    // Alerts keep the colour of their position in the list, so a band and the row that
    // describes it match even when the range leaves out the alerts in between. Taking no more
    // than the palette holds is what makes that hold: wrapping round would give two events on
    // one chart the same colour, which is exactly the reading the colours exist to prevent.
    val palette = alertColorPalette()
    val alertBands = remember(alerts, window, palette) {
        alerts.take(palette.size).mapIndexedNotNull { index, alert ->
            if (!window.covers(alert)) return@mapIndexedNotNull null
            AlertBand(
                startX = window.xOf(alert.start),
                endX = window.xOf(alert.end),
                color = palette[index]
            )
        }
    }

    // See RISK_FADE_DELAY_MILLIS. Keyed on the edges as well as the shading itself: a change of
    // range moves the windows without rewriting them, and a change of alert sensitivity
    // rewrites them without touching a single reading. Both land in one frame, so both need
    // standing aside for.
    // Rebuilt at zero rather than reset by an effect. Effects run after the frame they belong
    // to is drawn, so resetting in one paints the new shading once at full strength over edges
    // that have not started travelling yet, and only then takes it away: it flashes in,
    // vanishes and fades in again. Recreating the Animatable happens during composition, so
    // the very first frame is already at zero.
    val riskAlpha = remember(edges, alertBands) { Animatable(0f) }
    LaunchedEffect(riskAlpha) {
        riskAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(RISK_FADE_MILLIS, delayMillis = RISK_FADE_DELAY_MILLIS)
        )
    }

    // Straight off the edges, which already are the extremes whichever way they were built.
    // Neither can be empty here: both renderings build the pair from one source, so the guard
    // above covers them together.
    val dataMin = edges.lower.minOf { it.y }
    val dataMax = edges.upper.maxOf { it.y }
    val yPadding = maxOf((dataMax - dataMin) * 0.2f, 2f)
    val yMin = dataMin - yPadding
    val yMax = dataMax + yPadding

    // Mon/Tue/… for the 7-day chip, "3PM"/"9AM" for the hourly steps
    val labelFormatter = remember(window.step) {
        val base = if (isDaily) AppDateFormats.WEEKDAY else AppDateFormats.HOUR
        base.withZone(ZoneId.systemDefault())
    }
    val dayFormatter = remember {
        AppDateFormats.WEEKDAY.withZone(ZoneId.systemDefault())
    }
    val xFormatter = remember(window, labelFormatter, dayFormatter) {
        val zone = ZoneId.systemDefault()
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
            val index = value.roundToInt()
            val anchorEpoch = window.epochSecondAt(index)
            val label = labelFormatter.format(Instant.ofEpochSecond(anchorEpoch))
            if (isDaily) {
                label
            } else {
                // Hourly windows can cross midnight, where bare hour labels turn ambiguous.
                // Mark day transitions: the first label and any label on a new calendar day
                // get the day name on a second line.
                val day = Instant.ofEpochSecond(anchorEpoch).atZone(zone).toLocalDate()
                val previousDay =
                    Instant.ofEpochSecond(anchorEpoch - stepSeconds).atZone(zone).toLocalDate()
                if (index == ChartWindow.POINT_INDICES.first || day != previousDay) {
                    "$label\n${dayFormatter.format(Instant.ofEpochSecond(anchorEpoch))}"
                } else {
                    label
                }
            }
        }
    }
    val yFormatter = remember {
        AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> "${value.toInt()}" }
    }

    // Dashed "now" line: the actual current time, which sits a fraction of a step past the
    // anchor the chart snapped to. Read once per window rather than at every recomposition —
    // the window is itself built around a reading of the clock, so this moves when that does,
    // and an unremembered `now` would make the decoration below impossible to remember at all.
    val nowX = remember(window) { window.xOf(Instant.now()) }

    // Written by the chart every frame and read by the decoration that draws alongside it, so
    // it outlives both: the decoration is rebuilt whenever any of its inputs change.
    val positions = remember { DrawnPositions() }

    // Deliberately not keyed on the fade: see ChartOverlayDecoration.riskAlpha.
    val decoration = remember(
        alertBands, drawn, edgeOffsetAt, positions, seriesColor, nowX, nowLineColor
    ) {
        ChartOverlayDecoration(
            alertBands = alertBands,
            rendering = drawn,
            edgeOffsetAt = edgeOffsetAt,
            positions = positions,
            seriesColor = seriesColor,
            riskAlpha = riskAlpha::value,
            nowX = nowX,
            nowLineColorArgb = nowLineColor.copy(alpha = NOW_LINE_ALPHA).toArgb(),
        )
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            // Both edges are drawn by Vico, which is what lets them tween between ranges. A
            // Line rendering draws the same edge twice, exactly on top of itself.
            val lineSpec = LineChart.LineSpec(
                lineColor = seriesColor.toArgb(),
                lineThicknessDp = RANGE_LINE_WIDTH_DP
            )
            // Hand-built rather than taken from Vico's lineChart(), which cannot return the
            // subclass. It does the same thing: remember one chart and re-apply the settings.
            val chart = remember(positions) { TweeningLineChart(positions) }.apply {
                lines = listOf(lineSpec, lineSpec)
                spacingDp = currentChartStyle.lineChart.spacing.value
                axisValuesOverrider = AxisValuesOverrider.fixed(minY = yMin, maxY = yMax)
                setDecorations(listOf(decoration))
            }

            Chart(
                chart = chart,
                chartModelProducer = modelProducer,
                startAxis = rememberStartAxis(valueFormatter = yFormatter),
                bottomAxis = rememberBottomAxis(
                    // Two lines so hourly labels can carry the day name at day transitions;
                    // centred so the short day name sits under the middle of the hour.
                    label = axisLabelComponent(
                        lineCount = 2,
                        textAlignment = Layout.Alignment.ALIGN_CENTER
                    ),
                    valueFormatter = xFormatter,
                    itemPlacer = remember(window.step) {
                        AxisItemPlacer.Horizontal.default(
                            spacing = 1,
                            shiftExtremeTicks = false,
                            addExtremeLabelPadding = !isDaily
                        )
                    }
                ),
                // Hourly labels mark exact instants, so they sit on the gridlines (FullWidth);
                // day labels describe a whole day, so they sit centred between them (Segmented,
                // whose cell edges fall on midnights because the daily points are noon-snapped
                // — an hour off either side of a DST change, which moves no label onto another
                // day; see ChartWindow.epochSecondAt).
                horizontalLayout = if (isDaily) HorizontalLayout.Segmented else HorizontalLayout.FullWidth(),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        ChartLegend(
            seriesColor = seriesColor,
            nowLineColor = nowLineColor,
            rendering = drawn,
            rangeLabel = rangeLegendLabel(window.step),
            alertColors = alertBands.map { it.color }
        )
    }
}

/**
 * What the band entry is called, which has to name the step: "daily min/max" over three-hourly
 * data would describe a spread the chart is not showing.
 *
 * Only the daily chip asks for a band today, so only the first branch is reached by any screen.
 * The others are what makes the rendering a per-chip choice rather than a rule — see
 * [com.radami.migrainewatch.ui.screens.pressure.TimeRange] — and they are covered by tests so
 * that changing one chip's rendering does not also need this rewriting.
 */
internal fun rangeLegendLabel(step: ChartStep): String = when (step) {
    ChartStep.OneDay -> "daily min/max"
    else -> "${step.hours}-hourly min/max"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(
    seriesColor: Color,
    nowLineColor: Color,
    rendering: ChartRendering,
    rangeLabel: String,
    alertColors: List<Color>,
) {
    // Wraps rather than clips: the risk entry appears and disappears with the data, and at a
    // large font scale the three entries no longer fit one line.
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // "now" always leads, so the legend stays stable when switching chart ranges.
        LegendEntry(label = "now") {
            LegendLine(color = nowLineColor.copy(alpha = NOW_LINE_ALPHA), dashed = true)
        }

        // The two are alternatives: a band replaces the sampled line rather than joining it.
        when (rendering) {
            ChartRendering.MinMaxBand ->
                LegendEntry(label = rangeLabel) { LegendRangeSwatch(color = seriesColor) }

            ChartRendering.Line ->
                LegendEntry(label = "pressure") { LegendLine(color = seriesColor) }
        }

        if (alertColors.isNotEmpty()) {
            // One swatch per shaded window, in chart order, so the legend says how many
            // risk periods are in view as well as what the shading means.
            LegendEntry(label = if (alertColors.size == 1) "risk window" else "risk windows") {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    alertColors.forEach { color ->
                        LegendSwatch(
                            color = color.copy(alpha = ALERT_BAND_ALPHA),
                            width = RISK_SWATCH_WIDTH
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean = false) {
    Canvas(modifier = Modifier.size(width = SWATCH_WIDTH, height = 2.dp)) {
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(),
            pathEffect = effect
        )
    }
}

/** A step's range as the chart draws it: a wash between two lines. */
@Composable
private fun LegendRangeSwatch(color: Color) {
    Canvas(modifier = Modifier.size(width = SWATCH_WIDTH, height = 10.dp)) {
        drawRect(color = color.copy(alpha = RANGE_BAND_ALPHA))

        // The edges sit fully inside the swatch, so neither stroke is clipped in half.
        val stroke = RANGE_LINE_WIDTH_DP.dp.toPx()
        listOf(stroke / 2, size.height - stroke / 2).forEach { y ->
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke
            )
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, width: Dp = SWATCH_WIDTH) {
    Canvas(modifier = Modifier.size(width = width, height = 10.dp)) {
        drawRect(color = color)
    }
}
