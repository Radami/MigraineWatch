package com.example.migrainetracker.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.example.migrainetracker.ui.theme.ChartMeasuredDark
import com.example.migrainetracker.ui.theme.ChartMeasuredLight
import com.example.migrainetracker.ui.theme.ChartNowLineDark
import com.example.migrainetracker.ui.theme.ChartNowLineLight
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private data class BandEntry(val index: Int, val minY: Float, val maxY: Float)

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
 * Draws the min/max shaded band (behind the chart line) and the "now" dashed line (above it)
 * using Vico's Decoration API, which provides exact chart data-area bounds.
 */
private class ChartOverlayDecoration(
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
 * @param stepHours interval between the 8 chart points: 3 for the 24 h chip, 6 for 48 h, 24 for 7 days.
 */
@Composable
fun PressureChart(
    historical: List<PressureReading>,
    forecast: List<PressureReading>,
    stepHours: Int,
    modifier: Modifier = Modifier,
    showLegend: Boolean = true
) {
    if (historical.isEmpty() && forecast.isEmpty()) return

    val stepSeconds = stepHours.toLong() * 3600L
    val nowEpoch = Instant.now().epochSecond

    // For the daily step, snap to local-timezone noon so the day label sits at the midpoint of each
    // calendar day. This makes the "now" line fall near the current day's label rather than halfway
    // to the next day's label (which midnight anchoring would cause in the morning hours).
    // For sub-day steps, floor to the nearest step boundary.
    val snappedNowEpoch = if (stepHours >= 24) {
        LocalDate.now(ZoneId.systemDefault())
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toEpochSecond()
    } else {
        (nowEpoch / stepSeconds) * stepSeconds
    }

    // Merged list used by all three derived entry computations — allocated once per cache miss.
    val all = remember(historical, forecast) { historical + forecast }

    // Chart indices 0..7 map to time offsets -3..+4 steps from snappedNow.
    // Historical: indices 0..3 (up to and including snappedNow)
    // Forecast:   indices 3..7 (snappedNow onward) — index 3 is shared so the lines connect.
    val historicalEntries = remember(all, snappedNowEpoch, stepSeconds) {
        (0..3).mapNotNull { i ->
            val anchorEpoch = snappedNowEpoch + (i - 3) * stepSeconds
            pressureAt(all, anchorEpoch)?.let { FloatEntry(i.toFloat(), it) }
        }
    }
    val forecastEntries = remember(all, snappedNowEpoch, stepSeconds) {
        (3..7).mapNotNull { i ->
            val anchorEpoch = snappedNowEpoch + (i - 3) * stepSeconds
            pressureAt(all, anchorEpoch)?.let { FloatEntry(i.toFloat(), it) }
        }
    }

    val bandEntries = if (stepHours >= 24) {
        remember(all, snappedNowEpoch, stepSeconds) {
            val half = stepSeconds / 2
            (0..7).mapNotNull { i ->
                val anchorEpoch = snappedNowEpoch + (i - 3) * stepSeconds
                val inWindow = all.filter { abs(it.dateTime.epochSecond - anchorEpoch) <= half }
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
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight

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

    // "ha" → "3PM"/"9AM" for hourly steps, "EEE" (Mon/Tue/…) for the 7-day chip
    val labelPattern = if (stepHours < 24) "ha" else "EEE"
    val labelFormatter = remember(labelPattern) {
        DateTimeFormatter.ofPattern(labelPattern, Locale.ENGLISH).withZone(ZoneId.systemDefault())
    }
    val xFormatter = remember(snappedNowEpoch, stepSeconds, labelFormatter) {
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
            val offset = value.roundToInt() - 3          // chart index → step offset (-3..4)
            val anchorEpoch = snappedNowEpoch + offset * stepSeconds
            labelFormatter.format(Instant.ofEpochSecond(anchorEpoch))
        }
    }
    val yFormatter = remember {
        AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> "${value.toInt()}" }
    }

    // Dashed "now" line: actual current time as a fractional x-value between index 3 and 4.
    val nowX = 3f + (nowEpoch - snappedNowEpoch).toFloat() / stepSeconds

    val showBand = bandEntries.size >= 2
    val decoration = remember(showBand, bandEntries, yMin, yMax, measuredColor, nowX, nowLineColor) {
        ChartOverlayDecoration(
            showBand = showBand,
            bandEntries = bandEntries,
            yMin = yMin,
            yMax = yMax,
            bandColorArgb = measuredColor.copy(alpha = 0.2f).toArgb(),
            nowX = nowX,
            nowLineColorArgb = nowLineColor.copy(alpha = 0.5f).toArgb(),
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
                    valueFormatter = xFormatter,
                    itemPlacer = remember(stepHours) {
                        AxisItemPlacer.Horizontal.default(
                            spacing = 1,
                            shiftExtremeTicks = false,
                            addExtremeLabelPadding = stepHours < 24
                        )
                    }
                ),
                // Hourly labels mark exact instants, so they sit on the gridlines (FullWidth);
                // day labels describe a whole day, so they sit centred between them (Segmented,
                // whose cell edges fall on midnights because the daily points are noon-snapped).
                horizontalLayout = if (stepHours < 24) HorizontalLayout.FullWidth() else HorizontalLayout.Segmented,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showLegend) {
            Spacer(Modifier.height(8.dp))
            ChartLegend(
                lineColor = measuredColor,
                nowLineColor = nowLineColor,
                showBand = showBand,
                bandColor = measuredColor
            )
        }
    }
}

@Composable
private fun ChartLegend(
    lineColor: androidx.compose.ui.graphics.Color,
    nowLineColor: androidx.compose.ui.graphics.Color,
    showBand: Boolean,
    bandColor: androidx.compose.ui.graphics.Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The pressure line is only drawn when the band isn't (24 h / 48 h ranges).
        if (!showBand) {
            LegendItem(color = lineColor, label = "pressure")
            Spacer(Modifier.width(12.dp))
        }
        LegendItem(color = nowLineColor, label = "now", dashed = true)
        if (showBand) {
            Spacer(Modifier.width(12.dp))
            LegendBandItem(color = bandColor, label = "daily range")
        }
    }
}

@Composable
private fun LegendItem(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    dashed: Boolean = false
) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 2.dp)) {
        val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2),
            end = Offset(size.width, size.height / 2),
            strokeWidth = 2.dp.toPx(),
            pathEffect = effect
        )
    }
    Spacer(Modifier.width(4.dp))
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}

@Composable
private fun LegendBandItem(
    color: androidx.compose.ui.graphics.Color,
    label: String
) {
    Canvas(modifier = Modifier.size(width = 24.dp, height = 10.dp)) {
        drawRect(color = color.copy(alpha = 0.2f))
    }
    Spacer(Modifier.width(4.dp))
    Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
}
