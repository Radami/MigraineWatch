package com.radami.migrainewatch.format

/**
 * A pressure value in hPa, to the tenth the alert threshold is set in.
 *
 * Goes through [AppLocale] for the decimal separator: unqualified formatting follows the
 * device, which writes "12,0" on a German phone and Arabic-Indic digits on an Arabic one,
 * either of which lands in the middle of an English sentence.
 */
fun formatHpa(hPa: Float): String = String.format(AppLocale.DISPLAY, "%.1f", hPa)
