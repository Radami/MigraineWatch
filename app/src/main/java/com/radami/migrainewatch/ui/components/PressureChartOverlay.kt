package com.radami.migrainewatch.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.patrykandpatrick.vico.core.Animation
import com.patrykandpatrick.vico.core.chart.DefaultPointConnector
import com.patrykandpatrick.vico.core.chart.decoration.Decoration
import com.patrykandpatrick.vico.core.chart.draw.ChartDrawContext
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.entry.ChartEntry
import com.patrykandpatrick.vico.core.entry.ChartEntryModel
import kotlin.math.abs
import kotlin.math.roundToInt

// Everything the chart draws that Vico does not: the risk shading, the band between the min and
// max edges, the strip of plot past the last point, and the "now" line. This is the half that
// needs a canvas, and so the half no unit test reaches — whatever can be decided without one is
// decided in PressureChartData.kt and arrives here already worked out.

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
internal const val RISK_FADE_DELAY_MILLIS = Animation.DIFF_DURATION / 2
internal const val RISK_FADE_MILLIS = Animation.DIFF_DURATION - RISK_FADE_DELAY_MILLIS

/** The dashed "now" line, in dp: stroke, then the on and off lengths of its dashes. */
private const val NOW_LINE_WIDTH_DP = 2f
private const val NOW_DASH_ON_DP = 10f
private const val NOW_DASH_OFF_DP = 6f

/**
 * How far a point has to sit from the plot edge before the strip between them is worth drawing.
 * Under a pixel there is nothing to carry, and a zero-length segment still lays down a stroke of
 * its own width.
 */
private const val MIN_OVERHANG_PX = 1f

/** Whether a traced run opens a new path contour or continues the one in progress. */
private enum class RunStart { MoveTo, LineTo }

/**
 * A point of the chart as it is on screen: its x index, and its two edges in pixels.
 *
 * The pixel twin of [RangeEntry] — one says what the chart plots, the other where that has
 * landed this frame, which mid-tween is not the same thing.
 */
private data class DrawnEntry(val index: Int, val minPx: Float, val maxPx: Float)

/** One alert's risk window, in chart x-values, in the colour of the row describing it. */
internal data class AlertBand(val startX: Float, val endX: Float, val color: Color)

/**
 * Where the chart's points sit this frame, as the fraction of the plot height each is drawn
 * at, by series and then by entry x. Empty when nothing is animating.
 *
 * Written by [TweeningLineChart] and read by [ChartOverlayDecoration], which have no other way
 * to reach each other: a chart is handed the model being interpolated, a decoration is not.
 */
internal class DrawnPositions {
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
internal class TweeningLineChart(private val positions: DrawnPositions) : LineChart() {

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
internal class ChartOverlayDecoration(
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
        nowPaint.strokeWidth = NOW_LINE_WIDTH_DP * density
        nowPaint.pathEffect =
            DashPathEffect(floatArrayOf(NOW_DASH_ON_DP * density, NOW_DASH_OFF_DP * density), 0f)
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
        for (run in consecutiveRuns(series) { it.index }) {
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

        if (abs(edgePx - fromPx) < MIN_OVERHANG_PX) return

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
