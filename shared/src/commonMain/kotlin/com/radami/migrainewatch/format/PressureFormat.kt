package com.radami.migrainewatch.format

import kotlin.math.abs
import kotlin.math.floor

private const val TENTHS_PER_HPA = 10
private const val ROUND_HALF_UP = 0.5

/**
 * A pressure value in hPa, to the tenth the alert threshold is set in. Written by hand with a
 * point for the decimal separator so it can't follow the device locale instead, and rounded
 * half up on the value as it reads, which is how the Android formatter always rounded it.
 */
fun formatHpa(hPa: Float): String {
    // A float widens to a double exactly and ten times it fits one too, so the rounding
    // below sees the float's true value rather than an error of its own.
    val tenths = floor(abs(hPa.toDouble()) * TENTHS_PER_HPA + ROUND_HALF_UP).toLong()
    val sign = if (hPa < 0f) "-" else ""

    return "$sign${tenths / TENTHS_PER_HPA}.${tenths % TENTHS_PER_HPA}"
}
