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

// Everything the chart draws that Vico does not: risk shading, the min/max band, the strip
// of plot past the last point, and the "now" line. Needs a canvas, so untested by unit tests;
// anything decidable without one lives in PressureChartData.kt.

/**
 * The risk shading can't travel with the tweening edges like the rest of the overlay does,
 * since its windows snap straight to their new position in one frame. So the old windows
 * fade out over the first half of a range change and the new ones fade in over the second,
 * overlapping the tween so it keeps redrawing.
 */
internal const val RISK_FADE_OUT_MILLIS = Animation.DIFF_DURATION / 2
internal const val RISK_FADE_IN_MILLIS = Animation.DIFF_DURATION - RISK_FADE_OUT_MILLIS

/** The dashed "now" line, in dp: stroke, then the on and off lengths of its dashes. */
private const val NOW_LINE_WIDTH_DP = 2f
private const val NOW_DASH_ON_DP = 10f
private const val NOW_DASH_OFF_DP = 6f

/** Minimum distance from the plot edge worth drawing an overhang for; below this a
 * zero-length segment would still lay down a stroke of its own width. */
private const val MIN_OVERHANG_PX = 1f

/** Gap between the drawn edges, in px, below which they coincide and enclose no wash: a
 * settled line, or a band that has finished tweening into one. */
private const val COLLAPSED_EDGE_PX = 0.5f

/** Whether a traced run opens a new path contour or continues the one in progress. */
private enum class RunStart { MoveTo, LineTo }

/**
 * A point of the chart as it is on screen: x index and two edges in pixels. The pixel twin
 * of [RangeEntry] — what the chart plots vs. where it has landed this frame, mid-tween.
 */
private data class DrawnEntry(val index: Int, val minPx: Float, val maxPx: Float) {
    val isCollapsed: Boolean get() = abs(minPx - maxPx) < COLLAPSED_EDGE_PX
}

/** One alert's risk window, in chart x-values, in the colour of the row describing it. */
internal data class AlertBand(val startX: Float, val endX: Float, val color: Color)

/**
 * Where the chart's points sit this frame, as fraction of plot height, by series then entry x.
 * Written by [TweeningLineChart] and read by [ChartOverlayDecoration] — their only shared link.
 */
internal class DrawnPositions {
    var byEntryX: List<Map<Float, Float>> = emptyList()
}

/**
 * A [LineChart] that publishes where it is drawing its points, so a [Decoration] (which
 * otherwise only sees the settled destination model) can track the mid-tween position too.
 */
internal class TweeningLineChart(private val positions: DrawnPositions) : LineChart() {

    override fun drawChartInternal(context: ChartDrawContext, model: ChartEntryModel) {
        positions.byEntryX = model.extraStore.getOrNull(drawingModelKey)
            .orEmpty()
            .map { series -> series.mapValues { (_, point) -> point.y } }

        // Decorations draw from inside here, so they see this frame, not the last one.
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
    /** Offset from a point out to the plot edge, or null if nothing to draw — see [edgeOffsetSampler]. */
    private val edgeOffsetAt: (Float, Int) -> Float?,
    private val positions: DrawnPositions,
    private val seriesColor: Color,
    /** Risk-shading fade progress, read fresh each draw rather than fixed at build time, so
     * the decoration need not be rebuilt every frame. See [RISK_FADE_OUT_MILLIS]. */
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

    // Butt-capped, not round, so it doesn't bulge past the plot edge where it joins Vico's line.
    private val overhangPaint = Paint(rangePaint).apply {
        strokeCap = Paint.Cap.BUTT
    }

    // Same connector Vico's line spec uses, so min/max lines curve like the Line rendering does.
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

    // Chart x-value to pixel, via the same formula Vico uses to place line points, so
    // overlays stay aligned in both FullWidth and Segmented layouts.
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
     * The series as it is on screen this frame, in pixels. Mid-tween, uses the positions
     * [TweeningLineChart] caught; once settled, falls back to each entry's own value. Covers
     * both renderings since a line is just a band whose edges coincide.
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

        // Between the risk shading and the edges Vico draws: the wash is the area they enclose.
        drawRangeBand(context, bounds, series)
        drawOverhangs(context, bounds, series)
    }

    private fun drawAlertBands(context: ChartDrawContext, bounds: RectF) {
        val alpha = riskAlpha()
        alertBands.forEach { band ->
            // An event can start before or run past the window; clip to the plot area and let
            // the list beside the chart account for the part cut off.
            val left = maxOf(context.dataX(band.startX, bounds), bounds.left)
            val right = minOf(context.dataX(band.endX, bounds), bounds.right)
            if (right <= left) return@forEach

            // Recomputed per frame rather than cached: cheaper than rebuilding the decoration.
            alertPaint.color = band.color.copy(alpha = ALERT_BAND_ALPHA * alpha).toArgb()
            context.canvas.drawRect(left, bounds.top, right, bounds.bottom, alertPaint)
        }
    }

    /**
     * Washes wherever the drawn edges are apart, not just when [rendering] is a band: on a
     * switch to a line the edges take the whole tween to meet, and the wash has to shrink
     * with them rather than vanish on the first frame.
     */
    private fun drawRangeBand(context: ChartDrawContext, bounds: RectF, series: List<DrawnEntry>) {
        if (series.isEmpty()) return

        val path = Path()
        for (run in consecutiveRuns(series) { it.index }) {
            // Edges already met along the whole run: a line, with nothing between to wash.
            if (run.all { it.isCollapsed }) {
                drawCollapsedRun(context, bounds, run)
                continue
            }

            // A lone step has no neighbour to fill towards, so draw it as a vertical instead.
            if (run.size == 1) {
                drawIsolatedStep(context, bounds, run.first())
                continue
            }

            // Only the fill; the edges themselves are drawn by Vico on top, so they can tween.
            drawBand(context, bounds, path, run)
        }
    }

    // A band day whose min and max match still gets its mark, as a dot; a line has none.
    private fun drawCollapsedRun(context: ChartDrawContext, bounds: RectF, run: List<DrawnEntry>) {
        if (rendering != ChartRendering.MinMaxBand || run.size != 1) return

        drawIsolatedStep(context, bounds, run.first())
    }

    // Traces down the max edge, back along the min edge, closing over the run's end verticals.
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
     * Carries the series out to the plot edges, since axis layout leaves a gap there (room
     * for the end labels, or the daily cell centring) that would otherwise read as missing
     * data under the risk shading. Drawn directly rather than added to the model, since it
     * isn't a real point the axis should give a cell or label to.
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

        // Offset is in hPa, point is in pixels: convert the rise, subtracting since screen y
        // grows downward.
        val chartValues = context.chartValuesProvider.getChartValues()
        val rise = offset / chartValues.lengthY * bounds.height()
        val fromMax = from.maxPx
        val fromMin = from.minPx
        val toMax = from.maxPx - rise
        val toMin = from.minPx - rise

        // Wash first, so the edges sit on top of it as they do over the run. Keyed on the
        // drawn gap for the same reason as drawRangeBand.
        if (!from.isCollapsed) {
            val path = Path().apply {
                moveTo(fromPx, fromMax)
                lineTo(edgePx, toMax)
                lineTo(edgePx, toMin)
                lineTo(fromPx, fromMin)
                close()
            }
            context.canvas.drawPath(path, bandPaint)
        }

        // Draw both edges even for a line, matching Vico's two coincident series.
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
