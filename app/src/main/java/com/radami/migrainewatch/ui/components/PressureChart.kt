package com.radami.migrainewatch.ui.components

import android.text.Layout
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.style.currentChartStyle
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.layout.HorizontalLayout
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Headroom above and below the data so the line doesn't run along the frame: a fraction of
 * the spread, with a floor in hPa for flat stretches that barely moved. */
private const val Y_PADDING_FRACTION = 0.2f
private const val MIN_Y_PADDING_HPA = 2f

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
    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(edges) {
        modelProducer.setEntries(listOf(edges.lower, edges.upper))
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

    // See RISK_FADE_DELAY_MILLIS. Keyed on edges and bands since either can change without
    // the other. Rebuilt at zero during composition rather than reset in an effect, which
    // would flash the old shading in for a frame before fading it back out.
    val riskAlpha = remember(edges, alertBands) { Animatable(0f) }
    LaunchedEffect(riskAlpha) {
        riskAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(RISK_FADE_MILLIS, delayMillis = RISK_FADE_DELAY_MILLIS)
        )
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
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
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
        AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> "${value.toInt()}" }
    }

    // Dashed "now" line position. Read once per window, not every recomposition, so the
    // decoration below can be remembered too.
    val nowX = remember(window) { window.xOf(Instant.now()) }

    // Written by the chart every frame, read by the decoration alongside it; outlives both.
    val positions = remember { DrawnPositions() }

    // Deliberately not keyed on the fade: see ChartOverlayDecoration.riskAlpha.
    val decoration = remember(
        alertBands, rendering, edgeOffsetAt, positions, seriesColor, nowX, nowLineColor
    ) {
        ChartOverlayDecoration(
            alertBands = alertBands,
            rendering = rendering,
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
            // Both edges are drawn by Vico so they can tween; a Line rendering draws the
            // same edge twice, on top of itself.
            val lineSpec = LineChart.LineSpec(
                lineColor = seriesColor.toArgb(),
                lineThicknessDp = RANGE_LINE_WIDTH_DP
            )
            // Hand-built since Vico's lineChart() can't return this subclass.
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
                    // Two lines for the day-name row; centred under the hour label.
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
                // Hourly labels sit on gridlines (FullWidth); day labels sit centred in a
                // cell (Segmented) since daily points are noon-snapped — see ChartWindow.epochSecondAt.
                horizontalLayout = if (isDaily) HorizontalLayout.Segmented else HorizontalLayout.FullWidth(),
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
