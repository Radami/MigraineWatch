package com.radami.migrainewatch.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import com.radami.migrainewatch.ui.theme.ChartAxisLineDark
import com.radami.migrainewatch.ui.theme.ChartAxisLineLight
import com.radami.migrainewatch.ui.theme.ChartGridlineDark
import com.radami.migrainewatch.ui.theme.ChartGridlineLight
import com.radami.migrainewatch.ui.theme.alertColorPalette
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerDrawingModelInterpolator
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerDrawingModel
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.CartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.common.vicoTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Headroom above and below the data so the line doesn't run along the frame: a fraction of
 * the spread, with a floor in hPa for flat stretches that barely moved. */
private const val Y_PADDING_FRACTION = 0.2f
private const val MIN_Y_PADDING_HPA = 2f

/** Width of one cell before the chart stretches to fill its width. Only its ratio to
 * [DAILY_CELL_INSET] matters, which is what keeps daily points centred in their cells. */
private val POINT_SPACING = 32.dp

/** Half a cell either side of the daily chart, so each day's point sits mid-cell under its
 * label. Scales with the cells as the chart stretches. */
private val DAILY_CELL_INSET = POINT_SPACING / 2

private val AXIS_LABEL_SIZE = 12.sp

/** Hourly labels take a second line for the day name where the day changes. */
private const val AXIS_LABEL_LINES = 2

/**
 * @param readings the whole series, sorted by time; the chart samples only the instants
 *   [window] names.
 * @param window which slice of time the chart draws, and at what resolution.
 * @param requestedRendering sampled line or min/max band; a request, not a guarantee — a
 *   sparse series falls back to [ChartRendering.Line] via [renderingFor].
 * @param alerts risk windows to shade; only as many as the palette has colours are shown.
 * @param emptyContent shown when nothing is plottable; required since only the chart knows
 *   whether the data reaches [window]. Laid out under [modifier] like the chart itself.
 */
@Composable
fun PressureChart(
    readings: List<PressureReading>,
    window: ChartWindow,
    modifier: Modifier = Modifier,
    requestedRendering: ChartRendering = ChartRendering.Line,
    alerts: List<AlertWindow> = emptyList(),
    emptyContent: @Composable () -> Unit
) {
    // Remembered unconditionally, not inside a branch, so switching rendering doesn't change
    // the shape of the composition.
    val rangeEntries = remember(readings, window, requestedRendering) {
        stepRanges(readings, window, requestedRendering)
    }

    // What can actually be drawn; marks, line colour and legend all key off this from here on.
    val rendering = renderingFor(requestedRendering, rangeEntries)

    val edges = remember(readings, window, rendering, rangeEntries) {
        seriesEdges(readings, window, rendering, rangeEntries)
    }

    // Nothing to plot when none of the window's instants fall inside the readings (empty
    // table, or stale cache too old for the selected range). The one place this is decided,
    // since every part of the chart draws from these edges.
    if (edges.lower.isEmpty()) {
        Box(modifier) { emptyContent() }
        return
    }

    val edgeOffsetAt = remember(readings, window, rendering) {
        edgeOffsetSampler(readings, window, rendering)
    }

    // Both edges go through the model so Vico tweens them between ranges; the decoration
    // reads the same interpolated model — see drawnSeries in ChartOverlayDecoration.
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(edges) {
        modelProducer.runTransaction {
            lineModel {
                series(x = edges.lower.map { it.x }, y = edges.lower.map { it.y })
                series(x = edges.upper.map { it.x }, y = edges.upper.map { it.y })
            }
        }
    }

    val isDark = isSystemInDarkTheme()
    // One colour whichever way the data is drawn, so changing range changes only the shape.
    val seriesColor = if (isDark) ChartSeriesDark else ChartSeriesLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight

    // Alerts keep the colour of their list position so a band matches its row even when the
    // range skips alerts in between; capped to the palette size so colours never repeat.
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

    // See RISK_FADE_OUT_MILLIS. The shading on screen lags the requested bands, so the old
    // windows can fade out where they were before the new ones fade in.
    val riskAlpha = remember { Animatable(0f) }
    var shownBands by remember { mutableStateOf(alertBands) }

    // Keyed on edges and bands since either can change without the other. A change landing
    // mid-fade restarts from wherever the shading has got to, so it never jumps.
    LaunchedEffect(edges, alertBands) {
        // Nothing visible to fade out on first draw; wait instead, so the fade-in still
        // lines up with the second half of the tween.
        if (riskAlpha.value > 0f) {
            riskAlpha.animateTo(targetValue = 0f, animationSpec = tween(RISK_FADE_OUT_MILLIS))
        } else {
            delay(RISK_FADE_OUT_MILLIS.toLong())
        }

        // Swapped only while invisible, so the new windows never appear at full strength.
        shownBands = alertBands
        riskAlpha.animateTo(targetValue = 1f, animationSpec = tween(RISK_FADE_IN_MILLIS))
    }

    // Straight off the edges, already the extremes whichever way they were built; the guard
    // above ensures neither is empty here.
    val dataMin = edges.lower.minOf { it.y }
    val dataMax = edges.upper.maxOf { it.y }
    val yPadding = maxOf((dataMax - dataMin) * Y_PADDING_FRACTION, MIN_Y_PADDING_HPA)
    val yMin = dataMin - yPadding
    val yMax = dataMax + yPadding

    // Step decides labels and axis layout; only the marks depend on the rendering.
    val isDaily = window.step == ChartStep.OneDay

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
        val stepSeconds = window.step.seconds
        CartesianValueFormatter { _, value, _ ->
            val index = value.roundToInt()
            val anchorEpoch = window.epochSecondAt(index)
            val label = labelFormatter.format(Instant.ofEpochSecond(anchorEpoch))
            if (isDaily) {
                label
            } else {
                // Hourly windows can cross midnight; add the day name on a second line at
                // the first label and any day transition, to disambiguate.
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
        CartesianValueFormatter { _, value, _ -> "${value.toInt()}" }
    }

    // Dashed "now" line position. Read once per window, not every recomposition, so the
    // decoration below can be remembered too.
    val nowX = remember(window) { window.xOf(Instant.now()) }

    // Shared by the line layer and the decoration, and kept across every rebuild of either:
    // the layer tweens through it, the decoration reads each frame of that tween back from it.
    val tweenKey = remember { ExtraStore.Key<LineCartesianLayerDrawingModel>() }
    val drawingModelInterpolator = remember { CartesianLayerDrawingModelInterpolator.line() }

    // Deliberately not keyed on the fade: see ChartOverlayDecoration.riskAlpha.
    val decoration = remember(
        shownBands, rendering, edgeOffsetAt, tweenKey, seriesColor, nowX, nowLineColor
    ) {
        ChartOverlayDecoration(
            alertBands = shownBands,
            rendering = rendering,
            edgeOffsetAt = edgeOffsetAt,
            tweenKey = tweenKey,
            seriesColor = seriesColor,
            riskAlpha = riskAlpha::value,
            nowX = nowX,
            nowLineColor = nowLineColor.copy(alpha = NOW_LINE_ALPHA),
        )
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            // Both edges are drawn by Vico so they can tween; a Line rendering draws the
            // same edge twice, on top of itself. Round-capped as in Vico 1, whose caps cover
            // the joint where a line meets its butt-capped overhang at the plot edge.
            val line = LineCartesianLayer.rememberLine(
                fill = remember(seriesColor) { LineCartesianLayer.LineFill.single(Fill(seriesColor)) },
                stroke = remember {
                    LineCartesianLayer.LineStroke.Continuous(
                        thickness = RANGE_LINE_WIDTH_DP.dp,
                        cap = StrokeCap.Round
                    )
                },
                interpolator = SERIES_INTERPOLATOR
            )
            val rangeProvider = remember(yMin, yMax) {
                CartesianLayerRangeProvider.fixed(minY = yMin.toDouble(), maxY = yMax.toDouble())
            }
            val layer = remember(line, rangeProvider, decoration) {
                TweenSharingLineLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(line, line),
                    pointSpacing = POINT_SPACING,
                    rangeProvider = rangeProvider,
                    drawingModelInterpolator = drawingModelInterpolator,
                    tweenKey = tweenKey,
                    overlay = decoration
                )
            }

            // Hourly labels sit on gridlines; day labels sit centred in a cell, between
            // gridlines, since daily points are noon-snapped — see ChartWindow.epochSecondAt.
            val itemPlacer = remember(isDaily) {
                if (isDaily) {
                    HorizontalAxis.ItemPlacer.segmented(shiftExtremeLines = false)
                } else {
                    HorizontalAxis.ItemPlacer.aligned(
                        shiftExtremeLines = false,
                        addExtremeLabelPadding = true
                    )
                }
            }
            val layerPadding: (ExtraStore) -> CartesianLayerPadding = remember(isDaily) {
                val padding = if (isDaily) {
                    CartesianLayerPadding(scalableStart = DAILY_CELL_INSET, scalableEnd = DAILY_CELL_INSET)
                } else {
                    CartesianLayerPadding()
                }
                { padding }
            }

            // The app's body font rather than Vico's own default, so the axes read like the
            // legend beneath them and follow any change to the app's typography.
            val bodyText = MaterialTheme.typography.bodyLarge
            val axisLabelStyle = TextStyle(
                color = vicoTheme.textColor,
                fontFamily = bodyText.fontFamily,
                fontWeight = bodyText.fontWeight,
                fontSize = AXIS_LABEL_SIZE,
                textAlign = TextAlign.Center
            )

            // Vico 3 draws gridlines, axis lines and ticks in one opaque light grey that
            // disappears into the risk shading; these keep Vico 1's colours instead.
            val gridlineFill = remember(isDark) {
                Fill(if (isDark) ChartGridlineDark else ChartGridlineLight)
            }
            val axisLineFill = remember(isDark) {
                Fill(if (isDark) ChartAxisLineDark else ChartAxisLineLight)
            }
            val axisLine = rememberAxisLineComponent(fill = axisLineFill)
            val axisTick = rememberAxisTickComponent(fill = axisLineFill)
            val gridline = rememberAxisGuidelineComponent(fill = gridlineFill)

            val chart = rememberCartesianChart(
                layer,
                startAxis = VerticalAxis.rememberStart(
                    line = axisLine,
                    label = rememberAxisLabelComponent(style = axisLabelStyle),
                    tick = axisTick,
                    guideline = gridline,
                    valueFormatter = yFormatter,
                    // As many evenly spaced labels as fit. The default steps in round values,
                    // which the padded, non-round y-range leaves room for only one of.
                    itemPlacer = remember { VerticalAxis.ItemPlacer.count() }
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    line = axisLine,
                    tick = axisTick,
                    guideline = gridline,
                    // Two lines for the day-name row; centred under the hour label.
                    label = rememberAxisLabelComponent(
                        style = axisLabelStyle,
                        lineCount = AXIS_LABEL_LINES
                    ),
                    valueFormatter = xFormatter,
                    itemPlacer = itemPlacer
                ),
                layerPadding = layerPadding,
                decorations = listOf(decoration)
            )

            // A fixed window that always fits its width: nothing to scroll to or zoom into.
            CartesianChartHost(
                chart = chart,
                modelProducer = modelProducer,
                scrollState = rememberVicoScrollState(scrollEnabled = false),
                zoomState = rememberVicoZoomState(zoomEnabled = false),
                animationSpec = tween(SERIES_TWEEN_MILLIS),
                // The overlay can't follow Vico's sweep-in, so the chart appears settled.
                initialAnimationSpec = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        ChartLegend(
            seriesColor = seriesColor,
            nowLineColor = nowLineColor,
            rendering = rendering,
            rangeLabel = rangeLegendLabel(window.step),
            alertColors = alertBands.map { it.color }
        )
    }
}
