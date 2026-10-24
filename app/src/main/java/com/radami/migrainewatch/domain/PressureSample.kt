package com.radami.migrainewatch.domain

import java.time.Instant

/**
 * One pressure measurement as detection sees it: when, and the mean sea-level pressure in hPa.
 * The domain's own type rather than the stored reading, so detection doesn't depend on how
 * readings are persisted.
 */
data class PressureSample(
    val time: Instant,
    val pressureMsl: Float
)
