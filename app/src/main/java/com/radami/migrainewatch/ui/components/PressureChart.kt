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
 *   knows why they might not — so each says the half it can. Laid out under [modifier], as the
 *   chart itself is: it stands in the same place and has to take up the same width.
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
        Box(modifier) { emptyContent() }
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
