package com.radami.migrainewatch.data.preferences

import kotlin.math.abs

/**
 * How large a pressure drop must be before the app warns, as three presets rather than a
 * free value; outside this range produces either constant noise or no alerts. [HIGH] warns
 * on the smallest drop, so sensitivity runs opposite to the threshold.
 */
enum class AlertSensitivity(val thresholdHpa: Float) {
    HIGH(6f),
    MEDIUM(8f),
    LOW(10f);

    companion object {
        val Default = MEDIUM

        /** Closest preset to a stored threshold, so old free-value settings (3-15) still resolve. */
        fun forThreshold(hpa: Float): AlertSensitivity =
            entries.minBy { abs(it.thresholdHpa - hpa) }
    }
}
