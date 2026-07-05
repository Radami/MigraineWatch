package com.example.migrainetracker.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.domain.AlertWindow
import com.example.migrainetracker.ui.theme.ChartMeasuredDark
import com.example.migrainetracker.ui.theme.ChartMeasuredLight
import com.example.migrainetracker.ui.theme.ChartNowLineDark
import com.example.migrainetracker.ui.theme.ChartNowLineLight
import com.example.migrainetracker.ui.theme.alertColorPalette
import com.patrykandpatrick.vico.compose.axis.axisLabelComponent
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.axis.AxisItemPlacer
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.decoration.Decoration
import com.patrykandpatrick.vico.core.chart.dimensions.HorizontalDimensions
import com.patrykandpatrick.vico.core.chart.draw.ChartDrawContext
import com.patrykandpatrick.vico.core.chart.layout.HorizontalLayout
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.context.MeasureContext
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private class AlertDetailDecoration(
    private val alertRanges: List<Pair<Float, Float>>,
    private val alertHighlightColors: List<Int>,
    private val showNow: Boolean,
    private val nowX: Float,
    private val nowLineColorArgb: Int,
) : Decoration {

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = nowLineColorArgb
    }

    private var initialisedDensity = 0f

    private fun ensureNowPaintDensity(density: Float) {
        if (initialisedDensity == density) return
        initialisedDensity = density
        nowPaint.strokeWidth = 2f * density
        nowPaint.pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }

    override fun onDrawBehindChart(context: ChartDrawContext, bounds: RectF) {
        val hd = context.horizontalDimensions
        val chartValues = context.chartValuesProvider.getChartValues()

        fun getX(xValue: Float): Float =
            bounds.left + hd.startPadding + (xValue - chartValues.minX) / chartValues.xStep * hd.xSpacing - context.horizontalScroll

        alertRanges.forEachIndexed { i, range ->
            val startX = getX(range.first)
            val endX = getX(range.second)
            highlightPaint.color = alertHighlightColors.getOrElse(i) { alertHighlightColors.last() }
            context.canvas.drawRect(startX, bounds.top, endX, bounds.bottom, highlightPaint)
        }
    }

    override fun onDrawAboveChart(context: ChartDrawContext, bounds: RectF) {
        if (!showNow) return
        ensureNowPaintDensity(context.density)
        val hd = context.horizontalDimensions
        val chartValues = context.chartValuesProvider.getChartValues()

        val x = bounds.left + hd.startPadding + (nowX - chartValues.minX) / chartValues.xStep * hd.xSpacing - context.horizontalScroll
        context.canvas.drawLine(x, bounds.top, x, bounds.bottom, nowPaint)
    }
}

/**
 * Places axis labels at the centre of each 12 h interval (between consecutive data points) while
 * keeping ticks and gridlines on the data points themselves, so each label describes the interval
 * that starts at the gridline to its left.
 */
private object IntervalCenteredAxisItemPlacer : AxisItemPlacer.Horizontal {

    override fun getShiftExtremeTicks(context: ChartDrawContext): Boolean = false

    override fun getAddFirstLabelPadding(context: MeasureContext): Boolean = false

    override fun getAddLastLabelPadding(context: MeasureContext): Boolean = false

    override fun getLabelValues(
        context: ChartDrawContext,
        visibleXRange: ClosedFloatingPointRange<Float>,
        fullXRange: ClosedFloatingPointRange<Float>,
    ): List<Float> {
        val chartValues = context.chartValuesProvider.getChartValues()
        val step = chartValues.xStep
        return generateSequence(chartValues.minX + step / 2) { it + step }
            .takeWhile { it < chartValues.maxX }
            .toList()
    }

    override fun getMeasuredLabelValues(
        context: MeasureContext,
        horizontalDimensions: HorizontalDimensions,
        fullXRange: ClosedFloatingPointRange<Float>,
    ): List<Float> {
        val chartValues = context.chartValuesProvider.getChartValues()
        return listOf(chartValues.minX + chartValues.xStep / 2)
    }

    override fun getLineValues(
        context: ChartDrawContext,
        visibleXRange: ClosedFloatingPointRange<Float>,
        fullXRange: ClosedFloatingPointRange<Float>,
    ): List<Float> {
        val chartValues = context.chartValuesProvider.getChartValues()
        return generateSequence(chartValues.minX) { it + chartValues.xStep }
            .takeWhile { it <= chartValues.maxX }
            .toList()
    }

    override fun getStartHorizontalAxisInset(
        context: MeasureContext,
        horizontalDimensions: HorizontalDimensions,
        tickThickness: Float,
    ): Float = (tickThickness / 2 - horizontalDimensions.unscalableStartPadding).coerceAtLeast(0f)

    override fun getEndHorizontalAxisInset(
        context: MeasureContext,
        horizontalDimensions: HorizontalDimensions,
        tickThickness: Float,
    ): Float = (tickThickness / 2 - horizontalDimensions.unscalableEndPadding).coerceAtLeast(0f)
}

/**
 * A 72-hour pressure chart anchored to the current time.
 *
 * Shows 7 points across six 12 h intervals aligned to local AM/PM boundaries. The chart
 * starts one interval before the boundary preceding "now", so the now line always falls
 * within the second interval — roughly 12–24 h of past context, with the rest forecast.
 *
 * Every alert window is highlighted as a semi-transparent band whose bounds are computed
 * from the actual alert start/end regardless of the interval spacing, so each highlight
 * accurately covers its detected risk period.
 */
@Composable
fun AlertDetailChart(
    readings: List<PressureReading>,
    allAlerts: List<AlertWindow>,
    modifier: Modifier = Modifier
) {
    if (readings.isEmpty()) return

    val intervalSeconds = 12 * 3600L
    val zoneId = ZoneId.systemDefault()

    // Anchor the chart so it shows 6 intervals (7 points) = 72 hours, with "now" fixed in
    // the second interval: start one interval before the AM/PM boundary preceding now.
    val nowZoned = Instant.now().atZone(zoneId)
    val previousBoundary = nowZoned.toLocalDate()
        .atTime(if (nowZoned.hour < 12) 0 else 12, 0)
        .atZone(zoneId)

    val chartStartEpoch = previousBoundary.toEpochSecond() - intervalSeconds
    val chartTotalSeconds = 6 * intervalSeconds

    // 7 points at indices 0..6; points land exactly on 00:00 or 12:00.
    val entries = remember(readings, chartStartEpoch) {
        (0..6).mapNotNull { i ->
            val anchorEpoch = chartStartEpoch + i * intervalSeconds
            readings.minByOrNull { abs(it.dateTime.epochSecond - anchorEpoch) }
                ?.let { FloatEntry(i.toFloat(), it.pressureMsl) }
        }
    }

    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(entries) {
        modelProducer.setEntries(listOf(entries))
    }

    // X-axis values (indices) of all alert windows within the chart.
    val alertRanges = remember(allAlerts, chartStartEpoch) {
        allAlerts.map { alert ->
            val start = (alert.start.epochSecond - chartStartEpoch).toFloat() / intervalSeconds
            val end = (alert.end.epochSecond - chartStartEpoch).toFloat() / intervalSeconds
            Pair(start, end)
        }
    }

    val nowEpoch = Instant.now().epochSecond
    val showNow = nowEpoch > chartStartEpoch && nowEpoch < chartStartEpoch + chartTotalSeconds
    val nowX = (nowEpoch - chartStartEpoch).toFloat() / intervalSeconds

    val isDark = isSystemInDarkTheme()
    val lineColor = if (isDark) ChartMeasuredDark else ChartMeasuredLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight
    
    val alertColors = alertColorPalette().map { it.base }
    val alertHighlightColors = remember(allAlerts, alertColors) {
        allAlerts.mapIndexed { i, _ -> 
            alertColors[i % alertColors.size].copy(alpha = 0.15f).toArgb()
        }
    }

    val allY = entries.map { it.y }
    val dataMin = allY.minOrNull() ?: return
    val dataMax = allY.maxOrNull() ?: return
    val yPadding = maxOf((dataMax - dataMin) * 0.2f, 2f)

    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("a", Locale.ENGLISH).withZone(zoneId)
    }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH).withZone(zoneId)
    }
    val xFormatter = remember(chartStartEpoch, timeFormatter, dateFormatter) {
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
            // Labels sit at interval midpoints (i + 0.5); describe the interval by its start.
            val i = value.toInt()
            val anchorEpoch = chartStartEpoch + i * intervalSeconds
            val instant = Instant.ofEpochSecond(anchorEpoch).atZone(zoneId)
            
            val dayStr = dateFormatter.format(instant)
            val timeStr = timeFormatter.format(instant)
            
            "$dayStr\n$timeStr"
        }
    }
    val yFormatter = remember {
        AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> "${value.roundToInt()}" }
    }

    val decoration = remember(alertRanges, alertHighlightColors, showNow, nowX, nowLineColor) {
        AlertDetailDecoration(
            alertRanges = alertRanges,
            alertHighlightColors = alertHighlightColors,
            showNow = showNow,
            nowX = nowX,
            nowLineColorArgb = nowLineColor.copy(alpha = 0.5f).toArgb(),
        )
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            Chart(
                chart = lineChart(
                    lines = listOf(LineChart.LineSpec(lineColor = lineColor.toArgb())),
                    decorations = listOf(decoration),
                    axisValuesOverrider = AxisValuesOverrider.fixed(
                        minY = dataMin - yPadding,
                        maxY = dataMax + yPadding
                    )
                ),
                chartModelProducer = modelProducer,
                startAxis = rememberStartAxis(valueFormatter = yFormatter),
                bottomAxis = rememberBottomAxis(
                    label = axisLabelComponent(lineCount = 2),
                    valueFormatter = xFormatter,
                    itemPlacer = IntervalCenteredAxisItemPlacer
                ),
                horizontalLayout = HorizontalLayout.FullWidth(),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        AlertDetailLegend(
            lineColor = lineColor,
            alertColors = alertColors,
            nowLineColor = nowLineColor,
            showNow = showNow,
            alertCount = allAlerts.size
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertDetailLegend(
    lineColor: androidx.compose.ui.graphics.Color,
    alertColors: List<androidx.compose.ui.graphics.Color>,
    nowLineColor: androidx.compose.ui.graphics.Color,
    showNow: Boolean,
    alertCount: Int
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LegendItem("pressure") {
            Canvas(modifier = Modifier.size(width = 24.dp, height = 2.dp)) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        repeat(alertCount) { i ->
            LegendItem("alert ${i + 1}") {
                Canvas(modifier = Modifier.size(width = 24.dp, height = 10.dp)) {
                    drawRect(color = alertColors[i % alertColors.size].copy(alpha = 0.15f))
                }
            }
        }

        if (showNow) {
            LegendItem("now") {
                Canvas(modifier = Modifier.size(width = 24.dp, height = 2.dp)) {
                    drawLine(
                        color = nowLineColor.copy(alpha = 0.5f),
                        start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2),
                        strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, swatch: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
