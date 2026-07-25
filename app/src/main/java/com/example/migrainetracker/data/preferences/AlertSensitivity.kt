package com.example.migrainetracker.data.preferences

import kotlin.math.abs

/**
 * How large a pressure drop has to be before the app warns about it, offered as three presets
 * rather than a free value: thresholds outside this range produce either constant noise or no
 * alerts at all, so neither is worth letting a user pick.
 *
 * Sensitivity runs opposite to the threshold — [HIGH] warns on the smallest drop.
 */
enum class AlertSensitivity(val thresholdHpa: Float) {
    HIGH(6f),
    MEDIUM(8f),
    LOW(10f);

    companion object {
        val Default = MEDIUM

        /**
         * The level closest to a stored threshold, so a value saved by an earlier version of
         * the app (which offered any value from 3 to 15) still resolves to one of the presets.
         */
        fun forThreshold(hpa: Float): AlertSensitivity =
            entries.minBy { abs(it.thresholdHpa - hpa) }
    }
}
