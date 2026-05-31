package com.example.migrainetracker.ui.components

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

    // For the daily step, snap to local-timezone midnight so labels and anchor hours are consistent
    // regardless of the device's UTC offset. For sub-day steps, floor to the nearest step boundary.
    val snappedNowEpoch = if (stepHours >= 24) {
        LocalDate.now(ZoneId.systemDefault())
            .atStartOfDay(ZoneId.systemDefault())
            .toEpochSecond()
    } else {
        (nowEpoch / stepSeconds) * stepSeconds
    }

    // Chart indices 0..7 map to time offsets -3..+4 steps from snappedNow.
    // Historical: indices 0..3 (up to and including snappedNow)
    // Forecast:   indices 3..7 (snappedNow onward) — index 3 is shared so the lines connect.
    val historicalEntries = remember(historical, forecast, snappedNowEpoch, stepSeconds) {
        val all = historical + forecast
        (0..3).mapNotNull { i ->
            val anchorEpoch = snappedNowEpoch + (i - 3) * stepSeconds
            all.minByOrNull { abs(it.dateTime.epochSecond - anchorEpoch) }
                ?.let { FloatEntry(i.toFloat(), it.pressureMsl) }
        }
    }
    val forecastEntries = remember(historical, forecast, snappedNowEpoch, stepSeconds) {
        val all = historical + forecast
        (3..7).mapNotNull { i ->
            val anchorEpoch = snappedNowEpoch + (i - 3) * stepSeconds
            all.minByOrNull { abs(it.dateTime.epochSecond - anchorEpoch) }
                ?.let { FloatEntry(i.toFloat(), it.pressureMsl) }
        }
    }

    val modelProducer = remember { ChartEntryModelProducer() }
    LaunchedEffect(historicalEntries, forecastEntries) {
        modelProducer.setEntries(listOf(historicalEntries, forecastEntries))
    }

    val isDark = isSystemInDarkTheme()
    val measuredColor = if (isDark) ChartMeasuredDark else ChartMeasuredLight
    val nowLineColor = if (isDark) ChartNowLineDark else ChartNowLineLight

    val allY = (historicalEntries + forecastEntries).map { it.y }
    val dataMin = allY.minOrNull() ?: return
    val dataMax = allY.maxOrNull() ?: return
    val yPadding = maxOf((dataMax - dataMin) * 0.2f, 2f)

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

    // Dashed "now" line: actual current time as a fractional position between index 3 and 4
    val fractionInStep = (nowEpoch - snappedNowEpoch).toFloat() / stepSeconds
    val nowFraction = (3f + fractionInStep) / 7f   // 7 intervals between 8 points (indices 0..7)

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
        ) {
            Chart(
                chart = lineChart(
                    lines = listOf(
                        LineChart.LineSpec(lineColor = measuredColor.toArgb()),
                        LineChart.LineSpec(lineColor = measuredColor.toArgb())
                    ),
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

        if (showLegend) {
            Spacer(Modifier.height(8.dp))
            ChartLegend(nowLineColor = nowLineColor)
        }
    }
}

@Composable
private fun ChartLegend(
    nowLineColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegendItem(color = nowLineColor, label = "now", dashed = true)
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
