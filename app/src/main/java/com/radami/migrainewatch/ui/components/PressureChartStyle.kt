package com.radami.migrainewatch.ui.components

// How the chart's marks look, wherever they are drawn. The overlay paints them, the chart hands
// their stroke width to Vico, and the legend redraws them at swatch size — a legend drawn a shade
// off the thing it explains explains nothing, so the values live here rather than in all three.
//
// Every overlay is a wash rather than a fill: the data stays readable through all of them,
// including where a risk window and the daily range overlap.
internal const val ALERT_BAND_ALPHA = 0.15f
internal const val RANGE_BAND_ALPHA = 0.2f
internal const val NOW_LINE_ALPHA = 0.5f

/** Stroke width of the min and max lines, in dp. */
internal const val RANGE_LINE_WIDTH_DP = 2f
