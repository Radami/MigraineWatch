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
import com.radami.migrainewatch.ui.theme.ChartMeasuredDark
import com.radami.migrainewatch.ui.theme.ChartMeasuredLight
import com.radami.migrainewatch.ui.theme.ChartNowLineDark
import com.radami.migrainewatch.ui.theme.ChartNowLineLight
import com.radami.migrainewatch.ui.theme.ChartRangeBandDark
import com.radami.migrainewatch.ui.theme.ChartRangeBandLight
import com.radami.migrainewatch.ui.theme.alertColorPalette
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.decoration.Decoration
import com.patrykandpatrick.vico.core.chart.draw.ChartDrawContext
import com.patrykandpatrick.vico.core.chart.layout.HorizontalLayout
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// Every overlay is a wash over the plot rather than a fill: the data has to stay readable
// through all of them, including where a risk band and the daily range overlap.
private const val ALERT_BAND_ALPHA = 0.15f
private const val RANGE_BAND_ALPHA = 0.2f
private const val NOW_LINE_ALPHA = 0.5f

private val SWATCH_WIDTH = 24.dp

/** Risk gets one swatch per window in view, so they are narrowed to leave the legend on one line. */
private val RISK_SWATCH_WIDTH = 14.dp

private data class BandEntry(val index: Int, val minY: Float, val maxY: Float)

/** One alert's risk window, in chart x-values, in the colour of the row describing it. */
private data class AlertBand(val startX: Float, val endX: Float, val color: Color)

/**
 * Pressure at exactly [epoch], linearly interpolated between the two surrounding readings
 * ([readings] must be sorted by time). Returns null outside the data range, so a missing
 * stretch of data drops the chart point instead of silently reusing a reading from a
 * different time.
 */
private fun pressureAt(readings: List<PressureReading>, epoch: Long): Float? {
    val after = readings.firstOrNull { it.dateTime.epochSecond >= epoch } ?: return null
    if (after.dateTime.epochSecond == epoch) return after.pressureMsl
    val before = readings.lastOrNull { it.dateTime.epochSecond <= epoch } ?: return null
    val t0 = before.dateTime.epochSecond
    val t1 = after.dateTime.epochSecond
    val fraction = (epoch - t0).toFloat() / (t1 - t0)
    return before.pressureMsl + fraction * (after.pressureMsl - before.pressureMsl)
}

/**
 * Draws the alert risk bands and the min/max shaded band (behind the chart line) and the
 * "now" dashed line (above it) using Vico's Decoration API, which provides exact chart
 * data-area bounds.
 */
private class ChartOverlayDecoration(
    private val alertBands: List<AlertBand>,
    private val showBand: Boolean,
    private val bandEntries: List<BandEntry>,
    private val yMin: Float,
    private val yMax: Float,
    private val bandColorArgb: Int,
    private val nowX: Float,
    private val nowLineColorArgb: Int,
) : Decoration {

    // Group consecutive entries so gaps in data don't produce incorrect slanted polygon faces.
    private val bandRuns: List<List<BandEntry>> = buildList {
        var current = mutableListOf<BandEntry>()
        for (entry in bandEntries) {
            if (current.isEmpty() || entry.index == current.last().index + 1) {
                current.add(entry)
            } else {
                if (current.isNotEmpty()) add(current)
                current = mutableListOf(entry)
            }
        }
        if (current.isNotEmpty()) add(current)
    }

    private val alertPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val alertColorsArgb: List<Int> =
        alertBands.map { it.color.copy(alpha = ALERT_BAND_ALPHA).toArgb() }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = bandColorArgb
    }

    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = nowLineColorArgb
    }

    // Density-dependent now-line properties are initialised lazily on first draw.
    private var initialisedDensity = 0f

    private fun ensureNowPaintDensity(density: Float) {
        if (initialisedDensity == density) return
        initialisedDensity = density
        nowPaint.strokeWidth = 2f * density
        nowPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
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

    override fun onDrawBehindChart(context: ChartDrawContext, bounds: RectF) {
        // Risk bands go down first: the daily range reads as a detail of the line, so it has
        // to stay legible on top of whatever the risk shading paints.
        drawAlertBands(context, bounds)
        drawRangeBand(context, bounds)
    }

    private fun drawAlertBands(context: ChartDrawContext, bounds: RectF) {
        alertBands.forEachIndexed { index, band ->
            // An event can begin before the window or run past its end. Clipping to the plot
            // area shows the part that is in view; the rest is accounted for by the list
            // beside the chart, which marks what the current range cannot reach.
            val left = maxOf(context.dataX(band.startX, bounds), bounds.left)
            val right = minOf(context.dataX(band.endX, bounds), bounds.right)
            if (right <= left) return@forEachIndexed

            alertPaint.color = alertColorsArgb[index]
            context.canvas.drawRect(left, bounds.top, right, bounds.bottom, alertPaint)
        }
    }

    private fun drawRangeBand(context: ChartDrawContext, bounds: RectF) {
        if (!showBand || bandRuns.isEmpty()) return
        val yRange = yMax - yMin
        // In Android Canvas, Y increases downward: bounds.top = maxY, bounds.bottom = minY.
        fun valueToY(v: Float) = bounds.bottom - (v - yMin) / yRange * bounds.height()

        val path = Path()
        for (run in bandRuns) {
            path.reset()
            run.forEachIndexed { i, entry ->
                val x = context.dataX(entry.index.toFloat(), bounds)
                val y = valueToY(entry.maxY)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            run.reversed().forEach { entry ->
                path.lineTo(context.dataX(entry.index.toFloat(), bounds), valueToY(entry.minY))
            }
            path.close()
            context.canvas.drawPath(path, bandPaint)
        }
    }

    override fun onDrawAboveChart(context: ChartDrawContext, bounds: RectF) {
        ensureNowPaintDensity(context.density)
        val x = context.dataX(nowX, bounds)
        context.canvas.drawLine(x, bounds.top, x, bounds.bottom, nowPaint)
    }
}

/**
 * @param readings every reading the chart may draw from, sorted by time. The chart samples the
 *   eight instants [window] names out of these rather than plotting them one for one, so it is
 *   given the whole series and not the slice one range happens to need.
 * @param window which slice of time the chart draws, and at what resolution.
 * @param alerts risk windows to shade, in the order the caller lists them. Those
 *   [ChartWindow.covers] returns false for are left to the caller to account for — the chart
 *   cannot show them at this range — and only as many as the palette has colours are shaded,
 *   so no two bands on one chart can be the same colour.
 */
@Composable
fun PressureChart(
    readings: List<PressureReading>,
    window: ChartWindow,
    modifier: Modifier = Modifier,
    alerts: List<AlertWindow> = emptyList()
) {
    if (readings.isEmpty()) return

    val isDaily = window.step == ChartStep.OneDay
    val stepSeconds = window.step.seconds
    val nowEpoch = Instant.now().epochSecond

    // History and forecast are two line series so they can be styled apart, but both sample
    // the whole series: they share the anchor point, so the two lines connect there.
    val historicalEntries = remember(readings, window) {
        window.historyIndices.mapNotNull { i ->
            pressureAt(readings, window.epochSecondAt(i))?.let { FloatEntry(i.toFloat(), it) }
        }
    }
    val forecastEntries = remember(readings, window) {
        window.forecastIndices.mapNotNull { i ->
            pressureAt(readings, window.epochSecondAt(i))?.let { FloatEntry(i.toFloat(), it) }
        }
    }

    val bandEntries = if (isDaily) {
        remember(readings, window) {
            val half = stepSeconds / 2
            ChartWindow.POINT_INDICES.mapNotNull { i ->
                val anchorEpoch = window.epochSecondAt(i)
                val inWindow = readings.filter { abs(it.dateTime.epochSecond - anchorEpoch) <= half }
                if (inWindow.size < 2) null
                else BandEntry(i, inWindow.minOf { it.pressureMsl }, inWindow.maxOf { it.pressureMsl })
            }
        }
    } else emptyList()

    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(historicalEntries, forecastEntries) {
        modelProducer.setEntries(listOf(historicalEntries, forecastEntries))
    }

    val isDark = isSystemInDarkTheme()
    val measuredColor = if (isDark) ChartMeasuredDark else ChartMeasuredLight
    val rangeBandColor = if (isDark) ChartRangeBandDark else ChartRangeBandLight
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

    val dataMin = if (bandEntries.isNotEmpty())
        bandEntries.minOf { it.minY }
    else
        (historicalEntries + forecastEntries).minOfOrNull { it.y } ?: return
    val dataMax = if (bandEntries.isNotEmpty())
        bandEntries.maxOf { it.maxY }
    else
        (historicalEntries + forecastEntries).maxOfOrNull { it.y } ?: return
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
    // anchor the chart snapped to.
    val nowX = window.xOf(Instant.ofEpochSecond(nowEpoch))

    val showBand = bandEntries.size >= 2
    val decoration = remember(alertBands, showBand, bandEntries, yMin, yMax, rangeBandColor, nowX, nowLineColor) {
        ChartOverlayDecoration(
            alertBands = alertBands,
            showBand = showBand,
            bandEntries = bandEntries,
            yMin = yMin,
            yMax = yMax,
            bandColorArgb = rangeBandColor.copy(alpha = RANGE_BAND_ALPHA).toArgb(),
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
            val lineColor = if (showBand) android.graphics.Color.TRANSPARENT else measuredColor.toArgb()
            Chart(
                chart = lineChart(
                    lines = listOf(
                        LineChart.LineSpec(lineColor = lineColor),
                        LineChart.LineSpec(lineColor = lineColor)
                    ),
                    decorations = listOf(decoration),
                    axisValuesOverrider = AxisValuesOverrider.fixed(
                        minY = yMin,
                        maxY = yMax
                    )
                ),
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
            lineColor = measuredColor,
            nowLineColor = nowLineColor,
            showBand = showBand,
            bandColor = rangeBandColor,
            alertColors = alertBands.map { it.color }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartLegend(
    lineColor: Color,
    nowLineColor: Color,
    showBand: Boolean,
    bandColor: Color,
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

        // The pressure line is only drawn when the band isn't (24 h / 48 h ranges).
        if (showBand) {
            LegendEntry(label = "daily range") {
                LegendSwatch(color = bandColor.copy(alpha = RANGE_BAND_ALPHA))
            }
        } else {
            LegendEntry(label = "pressure") { LegendLine(color = lineColor) }
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

@Composable
private fun LegendSwatch(color: Color, width: Dp = SWATCH_WIDTH) {
    Canvas(modifier = Modifier.size(width = width, height = 10.dp)) {
        drawRect(color = color)
    }
}
