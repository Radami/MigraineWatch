package com.example.migrainetracker.ui.components

import android.graphics.DashPathEffect
import android.graphics.Paint
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
import com.patrykandpatrick.vico.core.chart.line.LineChart
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.ChartEntryModelProducer
import com.patrykandpatrick.vico.core.entry.FloatEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private class AlertDetailDecoration(
    private val alertStartFraction: Float,
    private val alertEndFraction: Float,
    private val alertHighlightColorArgb: Int,
    private val showNow: Boolean,
    private val nowFraction: Float,
    private val nowLineColorArgb: Int,
) : Decoration {

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = alertHighlightColorArgb
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
        val startX = bounds.left + alertStartFraction * bounds.width()
        val endX = bounds.left + alertEndFraction * bounds.width()
        context.canvas.drawRect(startX, bounds.top, endX, bounds.bottom, highlightPaint)
    }

    override fun onDrawAboveChart(context: ChartDrawContext, bounds: RectF) {
        if (!showNow) return
        ensureNowPaintDensity(context.density)
        val x = bounds.left + nowFraction * bounds.width()
        context.canvas.drawLine(x, bounds.top, x, bounds.bottom, nowPaint)
    }
}

/**
 * A chart centered on an alert window rather than the current time.
 *
 * Shows 6 data points across 5 equal intervals. The interval size equals the alert duration,
 * capped at 9.6 h so the total visible range never exceeds 48 h. For a 3 h alert this gives
 * [−7.5 h … +7.5 h] from the alert centre; for a 24 h alert it gives [−24 h … +24 h].
 *
 * The alert window is highlighted as a semi-transparent band whose bounds are computed from
 * the actual alert start/end regardless of the interval spacing, so the highlight always
 * accurately covers the detected risk period.
 */
@Composable
fun AlertDetailChart(
    readings: List<PressureReading>,
    alertStartEpoch: Long,
    alertEndEpoch: Long,
    modifier: Modifier = Modifier
) {
    if (readings.isEmpty()) return

    val intervalSeconds = 12 * 3600L
    val alertCenterEpoch = (alertStartEpoch + alertEndEpoch) / 2L
    val zoneId = ZoneId.systemDefault()

    // Anchor the chart so it shows 6 intervals (7 points) = 72 hours.
    // Centered around the full day of the alert: index 3 should be noon of the alert day.
    val alertDateTime = Instant.ofEpochSecond(alertCenterEpoch).atZone(zoneId)
    val snappedCenterEpoch = alertDateTime.toLocalDate()
        .atTime(12, 0)
        .atZone(zoneId)
        .toEpochSecond()

    val chartStartEpoch = snappedCenterEpoch - 3 * intervalSeconds
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

    // Fractional [0..1] positions of the alert window and current time within the chart.
    val alertStartFraction = ((alertStartEpoch - chartStartEpoch).toDouble() / chartTotalSeconds).toFloat().coerceIn(0f, 1f)
    val alertEndFraction = ((alertEndEpoch - chartStartEpoch).toDouble() / chartTotalSeconds).toFloat().coerceIn(0f, 1f)
    val nowEpoch = Instant.now().epochSecond
    val showNow = nowEpoch > chartStartEpoch && nowEpoch < chartStartEpoch + chartTotalSeconds
    val nowFraction = ((nowEpoch - chartStartEpoch).toDouble() / chartTotalSeconds).toFloat().coerceIn(0f, 1f)

    val isDark = isSystemInDarkTheme()
    val lineColor = if (isDark) ChartMeasuredDark else ChartMeasuredLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight
    val alertHighlightColor = MaterialTheme.colorScheme.error

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
            val i = value.roundToInt()
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

    val decoration = remember(alertStartFraction, alertEndFraction, alertHighlightColor, showNow, nowFraction, nowLineColor) {
        AlertDetailDecoration(
            alertStartFraction = alertStartFraction,
            alertEndFraction = alertEndFraction,
            alertHighlightColorArgb = alertHighlightColor.copy(alpha = 0.15f).toArgb(),
            showNow = showNow,
            nowFraction = nowFraction,
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
                    itemPlacer = remember { AxisItemPlacer.Horizontal.default(spacing = 1, shiftExtremeTicks = false, addExtremeLabelPadding = false) }
                ),
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(Modifier.height(8.dp))
        AlertDetailLegend(
            lineColor = lineColor,
            alertHighlightColor = alertHighlightColor,
            nowLineColor = nowLineColor,
            showNow = showNow,
        )
    }
}

@Composable
private fun AlertDetailLegend(
    lineColor: androidx.compose.ui.graphics.Color,
    alertHighlightColor: androidx.compose.ui.graphics.Color,
    nowLineColor: androidx.compose.ui.graphics.Color,
    showNow: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(width = 24.dp, height = 2.dp)) {
            drawLine(
                color = lineColor,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 2.dp.toPx()
            )
        }
        Spacer(Modifier.width(4.dp))
        Text("pressure", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

        Spacer(Modifier.width(12.dp))

        Canvas(modifier = Modifier.size(width = 24.dp, height = 10.dp)) {
            drawRect(color = alertHighlightColor.copy(alpha = 0.15f))
        }
        Spacer(Modifier.width(4.dp))
        Text("alert window", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))

        if (showNow) {
            Spacer(Modifier.width(12.dp))
            Canvas(modifier = Modifier.size(width = 24.dp, height = 2.dp)) {
                drawLine(
                    color = nowLineColor.copy(alpha = 0.5f),
                    start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            }
            Spacer(Modifier.width(4.dp))
            Text("now", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        }
    }
}
