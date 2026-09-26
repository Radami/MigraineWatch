package com.radami.migrainewatch.format

/**
 * A pressure value in hPa, to the tenth the alert threshold is set in. Goes through
 * [AppLocale] so the decimal separator doesn't follow the device locale instead.
 */
fun formatHpa(hPa: Float): String = String.format(AppLocale.DISPLAY, "%.1f", hPa)
