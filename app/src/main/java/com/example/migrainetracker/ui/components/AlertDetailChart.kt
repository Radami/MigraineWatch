package com.example.migrainetracker.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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

    val alertDurationSeconds = (alertEndEpoch - alertStartEpoch).coerceAtLeast(1L)
    val alertDurationHours = alertDurationSeconds / 3600f
    // Cap so 5 × interval ≤ 48 h
    val intervalHours = minOf(alertDurationHours, 48f / 5f)
    val intervalSeconds = (intervalHours * 3600f).toLong()
    val alertCenterEpoch = (alertStartEpoch + alertEndEpoch) / 2L

    // 6 points at indices 0..5; centre of the chart sits between indices 2 and 3 (at 2.5).
    // Point i → epoch = alertCenter + (i − 2.5) × interval
    val entries = remember(readings, alertCenterEpoch, intervalSeconds) {
        (0..5).mapNotNull { i ->
            val anchorEpoch = alertCenterEpoch + ((i.toDouble() - 2.5) * intervalSeconds).toLong()
            readings.minByOrNull { abs(it.dateTime.epochSecond - anchorEpoch) }
                ?.let { FloatEntry(i.toFloat(), it.pressureMsl) }
        }
    }

    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(entries) {
        modelProducer.setEntries(listOf(entries))
    }

    // Chart edges in epoch seconds
    val chartStartEpoch = alertCenterEpoch - (2.5 * intervalSeconds).toLong()
    val chartTotalSeconds = 5.0 * intervalSeconds

    // Fractional [0..1] positions of the alert window and current time within the chart
    val alertStartFraction = ((alertStartEpoch - chartStartEpoch) / chartTotalSeconds)
        .toFloat().coerceIn(0f, 1f)
    val alertEndFraction = ((alertEndEpoch - chartStartEpoch) / chartTotalSeconds)
        .toFloat().coerceIn(0f, 1f)
    val nowEpoch = Instant.now().epochSecond
    val nowFraction = ((nowEpoch - chartStartEpoch) / chartTotalSeconds)
        .toFloat().coerceIn(0f, 1f)

    val isDark = isSystemInDarkTheme()
    val lineColor = if (isDark) ChartMeasuredDark else ChartMeasuredLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight
    val alertHighlightColor = MaterialTheme.colorScheme.error

    val allY = entries.map { it.y }
    val dataMin = allY.minOrNull() ?: return
    val dataMax = allY.maxOrNull() ?: return
    val yPadding = maxOf((dataMax - dataMin) * 0.2f, 2f)

    // Show day name alongside hour when the chart spans more than one calendar day
    val labelPattern = if (intervalHours < 6f) "ha" else "EEE ha"
    val labelFormatter = remember(labelPattern) {
        DateTimeFormatter.ofPattern(labelPattern, Locale.ENGLISH).withZone(ZoneId.systemDefault())
    }
    val xFormatter = remember(alertCenterEpoch, intervalSeconds, labelFormatter) {
        AxisValueFormatter<AxisPosition.Horizontal.Bottom> { value, _ ->
            val anchorEpoch = alertCenterEpoch + ((value.toDouble() - 2.5) * intervalSeconds).toLong()
            labelFormatter.format(Instant.ofEpochSecond(anchorEpoch))
        }
    }
    val yFormatter = remember {
        AxisValueFormatter<AxisPosition.Vertical.Start> { value, _ -> "${value.roundToInt()}" }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        Chart(
            chart = lineChart(
                lines = listOf(LineChart.LineSpec(lineColor = lineColor.toArgb())),
                axisValuesOverrider = AxisValuesOverrider.fixed(
                    minY = dataMin - yPadding,
                    maxY = dataMax + yPadding
                )
            ),
            chartModelProducer = modelProducer,
            startAxis = rememberStartAxis(valueFormatter = yFormatter),
            bottomAxis = rememberBottomAxis(
                valueFormatter = xFormatter,
                itemPlacer = remember { AxisItemPlacer.Horizontal.default(spacing = 1) }
            ),
            modifier = Modifier.fillMaxSize()
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Semi-transparent alert window band
            val startX = alertStartFraction * size.width
            val endX = alertEndFraction * size.width
            drawRect(
                color = alertHighlightColor.copy(alpha = 0.15f),
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height)
            )

            // "Now" dashed line — only draw if it falls within the chart window
            if (nowFraction > 0f && nowFraction < 1f) {
                val nowX = nowFraction * size.width
                drawLine(
                    color = nowLineColor.copy(alpha = 0.5f),
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, size.height),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))
                )
            }
        }
    }
}
