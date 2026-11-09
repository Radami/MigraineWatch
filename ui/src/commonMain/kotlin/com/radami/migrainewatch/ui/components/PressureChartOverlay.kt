package com.radami.migrainewatch.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerDrawingModelInterpolator
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerDrawingModel
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.abs
import kotlin.math.roundToInt

// Everything the chart draws that Vico does not: risk shading, the min/max band, the strip
// of plot past the last point, and the "now" line. Needs a canvas, so untested by unit tests;
// anything decidable without one lives in PressureChartData.kt.

/**
 * How long Vico takes to tween the edges between ranges. Passed to the chart host explicitly,
 * rather than left to Vico's default, since the risk fade below is timed against it.
 */
internal const val SERIES_TWEEN_MILLIS = 500

/**
 * The risk shading can't travel with the tweening edges like the rest of the overlay does,
 * since its windows snap straight to their new position in one frame. So the old windows
 * fade out over the first half of a range change and the new ones fade in over the second,
 * overlapping the tween so it keeps redrawing.
 */
internal const val RISK_FADE_OUT_MILLIS = SERIES_TWEEN_MILLIS / 2
internal const val RISK_FADE_IN_MILLIS = SERIES_TWEEN_MILLIS - RISK_FADE_OUT_MILLIS

/**
 * How the edges curve between points. Shared by Vico's lines and the band fill traced here,
 * so the wash always meets the lines it sits between.
 */
internal val SERIES_INTERPOLATOR: LineCartesianLayer.Interpolator =
    LineCartesianLayer.Interpolator.cubic()

/** The dashed "now" line: stroke, then the on and off lengths of its dashes. */
private val NOW_LINE_WIDTH = 2.dp
private val NOW_DASH_ON = 10.dp
private val NOW_DASH_OFF = 6.dp

/** Minimum distance from the plot edge worth drawing an overhang for; below this a
 * zero-length segment would still lay down a stroke of its own width. */
private const val MIN_OVERHANG_PX = 1f

/** Gap between the drawn edges, in px, below which they coincide and enclose no wash: a
 * settled line, or a band that has finished tweening into one. */
private const val COLLAPSED_EDGE_PX = 0.5f

/** Series order in the chart model: the lower edge first, then the upper. */
private const val LOWER_SERIES_INDEX = 0
private const val UPPER_SERIES_INDEX = 1

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
 * A [LineCartesianLayer] built around a tween key the caller holds, so the overlay can read
 * the frame the lines are drawn from: Vico stores each frame of the tween in the drawing
 * context's extra store under this key, and the overlay sees the same store.
 *
 * Also draws [overlay]'s underlay just before the lines. Vico draws decorations before the
 * gridlines, which would leave the gridlines on top of the risk shading and the band wash;
 * drawn from here the underlay lands between the gridlines and the lines instead.
 *
 * Vico's own factory keeps the key and the stateful [drawingModelInterpolator] alive across
 * rebuilds by copying the previous layer. This one can't, so the caller remembers both and
 * hands the same instances to every rebuild, or each would restart the tween.
 */
internal class TweenSharingLineLayer(
    lineProvider: LineProvider,
    pointSpacing: Dp,
    rangeProvider: CartesianLayerRangeProvider,
    drawingModelInterpolator: CartesianLayerDrawingModelInterpolator<
        LineCartesianLayerDrawingModel.Entry,
        LineCartesianLayerDrawingModel,
        >,
    tweenKey: ExtraStore.Key<LineCartesianLayerDrawingModel>,
    private val overlay: ChartOverlayDecoration,
) : LineCartesianLayer(
    lineProvider,
    pointSpacing,
    rangeProvider,
    null,
    drawingModelInterpolator,
    tweenKey,
) {
    override fun drawInternal(context: CartesianDrawingContext, model: LineCartesianLayerModel) {
        overlay.drawUnderlay(context)
        super.drawInternal(context, model)
    }

    // Vico's equality covers only the line setup, and rememberCartesianChart keys on it. The
    // overlay is part of what this layer draws, so a different one must not count as unchanged.
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TweenSharingLineLayer && overlay == other.overlay && super.equals(other)

    override fun hashCode(): Int = 31 * super.hashCode() + overlay.hashCode()
}

/**
 * Wraps a path so the first `moveTo` an [LineCartesianLayer.Interpolator] issues carries on
 * from the current point instead of starting a new contour. That lets the band fill trace
 * back along its lower edge with the same interpolator, closing one shape rather than two.
 */
private class ContinuingPath(private val target: Path) : Path by target {
    override fun moveTo(x: Float, y: Float) = target.lineTo(x, y)
}

/**
 * Draws the alert risk bands and the daily min/max range behind the chart line (see
 * [drawUnderlay]), and the "now" dashed line above it as a Vico decoration.
 */
internal class ChartOverlayDecoration(
    private val alertBands: List<AlertBand>,
    private val rendering: ChartRendering,
    /** Offset from a point out to the plot edge, or null if nothing to draw — see [edgeOffsetSampler]. */
    private val edgeOffsetAt: (Float, Int) -> Float?,
    /** Where the edges are this frame; see [TweenSharingLineLayer]. */
    private val tweenKey: ExtraStore.Key<LineCartesianLayerDrawingModel>,
    private val seriesColor: Color,
    /** Risk-shading fade progress, read fresh each draw rather than fixed at build time, so
     * the decoration need not be rebuilt every frame. See [RISK_FADE_OUT_MILLIS]. */
    private val riskAlpha: () -> Float,
    private val nowX: Float,
    private val nowLineColor: Color,
) : Decoration {

    private val alertPaint = Paint().apply {
        style = PaintingStyle.Fill
    }

    private val bandPaint = Paint().apply {
        style = PaintingStyle.Fill
        color = seriesColor.copy(alpha = RANGE_BAND_ALPHA)
    }

    private val rangePaint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Round
        strokeJoin = StrokeJoin.Round
        color = seriesColor
    }

    // Butt-capped, not round, so it doesn't bulge past the plot edge where it joins Vico's line.
    private val overhangPaint = Paint().apply {
        style = PaintingStyle.Stroke
        strokeCap = StrokeCap.Butt
        color = seriesColor
    }

    private val nowPaint = Paint().apply {
        style = PaintingStyle.Stroke
        color = nowLineColor
    }

    // Density the now line's dash effect was last built for; 0 until the first draw.
    private var dashDensity = 0f

    // Stroke widths and dashes are in dp, so they are resolved against the context's density
    // on every draw rather than fixed at construction.
    private fun CartesianDrawingContext.applyDensity() {
        rangePaint.strokeWidth = RANGE_LINE_WIDTH_DP.dp.pixels
        overhangPaint.strokeWidth = RANGE_LINE_WIDTH_DP.dp.pixels
        nowPaint.strokeWidth = NOW_LINE_WIDTH.pixels

        // The dash effect is an allocation, so it is rebuilt only when the density changes,
        // not on every frame of a tween.
        if (density.density == dashDensity) return
        dashDensity = density.density
        nowPaint.pathEffect =
            PathEffect.dashPathEffect(floatArrayOf(NOW_DASH_ON.pixels, NOW_DASH_OFF.pixels), 0f)
    }

    // Chart x-value to pixel, via the same formula Vico uses to place line points, so
    // overlays stay aligned whatever the layer padding.
    private fun CartesianDrawingContext.dataX(x: Float): Float =
        layerBounds.left + layerDimensions.startPadding - scroll +
            layerDimensions.xSpacing * ((x - ranges.minX) / ranges.xStep).toFloat()

    /** [dataX] read backwards: which chart x-value the pixel column [px] stands for. */
    private fun CartesianDrawingContext.dataXInverse(px: Float): Float =
        (ranges.minX +
            (px - layerBounds.left - layerDimensions.startPadding + scroll) /
            layerDimensions.xSpacing * ranges.xStep).toFloat()

    /**
     * The series as it is on screen this frame, in pixels. Mid-tween, uses the frame Vico is
     * drawing the lines from; once settled, falls back to each entry's own value. Covers both
     * renderings since a line is just a band whose edges coincide.
     */
    private fun CartesianDrawingContext.drawnSeries(): List<DrawnEntry> {
        val layerModel = model.models.filterIsInstance<LineCartesianLayerModel>().firstOrNull()
            ?: return emptyList()
        val drawing = extraStore.getOrNull(tweenKey)
        val yRange = ranges.getYRange(null)

        fun pixelY(seriesIndex: Int, entry: LineCartesianLayerModel.Entry): Float {
            val fraction = drawing?.getOrNull(seriesIndex)?.get(entry.x)?.y
                ?: ((entry.y - yRange.minY) / yRange.length).toFloat()
            return layerBounds.bottom - fraction * layerBounds.height
        }

        val lower = layerModel.series.getOrNull(LOWER_SERIES_INDEX).orEmpty()
        val upper = layerModel.series.getOrNull(UPPER_SERIES_INDEX).orEmpty()
        return lower.zip(upper) { low, high ->
            DrawnEntry(
                index = low.x.roundToInt(),
                minPx = pixelY(LOWER_SERIES_INDEX, low),
                maxPx = pixelY(UPPER_SERIES_INDEX, high)
            )
        }
    }

    /**
     * Risk shading, band wash and edge overhangs: everything that sits behind the lines but
     * over the gridlines. Called by [TweenSharingLineLayer] rather than drawn as a decoration,
     * which Vico would put under the gridlines.
     */
    fun drawUnderlay(context: CartesianDrawingContext) {
        context.applyDensity()
        drawAlertBands(context)

        // Read once, so everything below describes the same frame of the same tween.
        val series = context.drawnSeries()

        // Between the risk shading and the edges Vico draws: the wash is the area they enclose.
        drawRangeBand(context, series)
        drawOverhangs(context, series)
    }

    private fun drawAlertBands(context: CartesianDrawingContext) {
        val bounds = context.layerBounds
        val alpha = riskAlpha()
        alertBands.forEach { band ->
            // An event can start before or run past the window; clip to the plot area and let
            // the list beside the chart account for the part cut off.
            val left = maxOf(context.dataX(band.startX), bounds.left)
            val right = minOf(context.dataX(band.endX), bounds.right)
            if (right <= left) return@forEach

            // Recomputed per frame rather than cached: cheaper than rebuilding the decoration.
            alertPaint.color = band.color.copy(alpha = ALERT_BAND_ALPHA * alpha)
            context.canvas.drawRect(Rect(left, bounds.top, right, bounds.bottom), alertPaint)
        }
    }

    /**
     * Washes wherever the drawn edges are apart, not just when [rendering] is a band: on a
     * switch to a line the edges take the whole tween to meet, and the wash has to shrink
     * with them rather than vanish on the first frame.
     */
    private fun drawRangeBand(context: CartesianDrawingContext, series: List<DrawnEntry>) {
        if (series.isEmpty()) return

        for (run in consecutiveRuns(series) { it.index }) {
            // Edges already met along the whole run: a line, with nothing between to wash.
            if (run.all { it.isCollapsed }) {
                drawCollapsedRun(context, run)
                continue
            }

            // A lone step has no neighbour to fill towards, so draw it as a vertical instead.
            if (run.size == 1) {
                drawIsolatedStep(context, run.first())
                continue
            }

            // Only the fill; the edges themselves are drawn by Vico on top, so they can tween.
            drawBand(context, run)
        }
    }

    // A band day whose min and max match still gets its mark, as a dot; a line has none.
    private fun drawCollapsedRun(context: CartesianDrawingContext, run: List<DrawnEntry>) {
        if (rendering != ChartRendering.MinMaxBand || run.size != 1) return

        drawIsolatedStep(context, run.first())
    }

    // Traces along the max edge, back along the min edge, closing over the run's end verticals.
    private fun drawBand(context: CartesianDrawingContext, run: List<DrawnEntry>) {
        val maxEdge = run.map { Offset(context.dataX(it.index.toFloat()), it.maxPx) }
        val minEdgeBack = run.asReversed().map { Offset(context.dataX(it.index.toFloat()), it.minPx) }

        val path = Path()
        SERIES_INTERPOLATOR.interpolate(context, path, maxEdge, maxEdge.indices)
        SERIES_INTERPOLATOR.interpolate(context, ContinuingPath(path), minEdgeBack, minEdgeBack.indices)
        path.close()
        context.canvas.drawPath(path, bandPaint)
    }

    /**
     * Carries the series out to the plot edges, since axis layout leaves a gap there (room
     * for the end labels, or the daily cell centring) that would otherwise read as missing
     * data under the risk shading. Drawn directly rather than added to the model, since it
     * isn't a real point the axis should give a cell or label to.
     */
    private fun drawOverhangs(context: CartesianDrawingContext, series: List<DrawnEntry>) {
        val first = series.firstOrNull() ?: return
        drawOverhang(context, first, context.layerBounds.left)
        drawOverhang(context, series.last(), context.layerBounds.right)
    }

    private fun drawOverhang(context: CartesianDrawingContext, from: DrawnEntry, edgePx: Float) {
        val fromPx = context.dataX(from.index.toFloat())

        if (abs(edgePx - fromPx) < MIN_OVERHANG_PX) return

        val offset = edgeOffsetAt(context.dataXInverse(edgePx), from.index) ?: return

        // Offset is in hPa, point is in pixels: convert the rise, subtracting since screen y
        // grows downward.
        val rise = (offset / context.ranges.getYRange(null).length).toFloat() *
            context.layerBounds.height
        val fromMax = Offset(fromPx, from.maxPx)
        val fromMin = Offset(fromPx, from.minPx)
        val toMax = Offset(edgePx, from.maxPx - rise)
        val toMin = Offset(edgePx, from.minPx - rise)

        // Wash first, so the edges sit on top of it as they do over the run. Keyed on the
        // drawn gap for the same reason as drawRangeBand.
        if (!from.isCollapsed) {
            val path = Path().apply {
                moveTo(fromMax.x, fromMax.y)
                lineTo(toMax.x, toMax.y)
                lineTo(toMin.x, toMin.y)
                lineTo(fromMin.x, fromMin.y)
                close()
            }
            context.canvas.drawPath(path, bandPaint)
        }

        // Draw both edges even for a line, matching Vico's two coincident series.
        context.canvas.drawLine(fromMax, toMax, overhangPaint)
        context.canvas.drawLine(fromMin, toMin, overhangPaint)
    }

    private fun drawIsolatedStep(context: CartesianDrawingContext, entry: DrawnEntry) {
        val x = context.dataX(entry.index.toFloat())
        context.canvas.drawLine(Offset(x, entry.maxPx), Offset(x, entry.minPx), rangePaint)
    }

    override fun drawOverLayers(context: CartesianDrawingContext) {
        context.applyDensity()

        val bounds = context.layerBounds
        val x = context.dataX(nowX)
        context.canvas.drawLine(Offset(x, bounds.top), Offset(x, bounds.bottom), nowPaint)
    }
}
