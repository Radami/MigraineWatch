package com.radami.migrainewatch.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radami.migrainewatch.domain.ChartStep

// What the marks under the chart stand for, drawn at swatch size from the same values.

private val SWATCH_WIDTH = 24.dp

/** Risk gets one swatch per window in view, so they are narrowed to leave the legend on one line. */
private val RISK_SWATCH_WIDTH = 14.dp

/**
 * What the band entry is called, which has to name the step: "daily min/max" over three-hourly
 * data would describe a spread the chart is not showing.
 *
 * Only the daily chip asks for a band today, so only the first branch is reached by any screen.
 * The others are what makes the rendering a per-chip choice rather than a rule — see
 * [com.radami.migrainewatch.ui.screens.pressure.TimeRange] — and they are covered by tests so
 * that changing one chip's rendering does not also need this rewriting.
 */
internal fun rangeLegendLabel(step: ChartStep): String = when (step) {
    ChartStep.OneDay -> "daily min/max"
    else -> "${step.hours}-hourly min/max"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChartLegend(
    seriesColor: Color,
    nowLineColor: Color,
    rendering: ChartRendering,
    rangeLabel: String,
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

        // The two are alternatives: a band replaces the sampled line rather than joining it.
        when (rendering) {
            ChartRendering.MinMaxBand ->
                LegendEntry(label = rangeLabel) { LegendRangeSwatch(color = seriesColor) }

            ChartRendering.Line ->
                LegendEntry(label = "pressure") { LegendLine(color = seriesColor) }
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

/** A step's range as the chart draws it: a wash between two lines. */
@Composable
private fun LegendRangeSwatch(color: Color) {
    Canvas(modifier = Modifier.size(width = SWATCH_WIDTH, height = 10.dp)) {
        drawRect(color = color.copy(alpha = RANGE_BAND_ALPHA))

        // The edges sit fully inside the swatch, so neither stroke is clipped in half.
        val stroke = RANGE_LINE_WIDTH_DP.dp.toPx()
        listOf(stroke / 2, size.height - stroke / 2).forEach { y ->
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = stroke
            )
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, width: Dp = SWATCH_WIDTH) {
    Canvas(modifier = Modifier.size(width = width, height = 10.dp)) {
        drawRect(color = color)
    }
}
