package com.radami.migrainewatch.ui.components

// Shared chart mark values: the overlay, the chart's Vico stroke width, and the legend swatch
// all draw from these so they never drift apart. Overlays are washes, not fills, so data stays
// readable even where a risk window and the daily range overlap.
internal const val ALERT_BAND_ALPHA = 0.15f
internal const val RANGE_BAND_ALPHA = 0.2f
internal const val NOW_LINE_ALPHA = 0.5f

/** Stroke width of the min and max lines, in dp. */
internal const val RANGE_LINE_WIDTH_DP = 2f
