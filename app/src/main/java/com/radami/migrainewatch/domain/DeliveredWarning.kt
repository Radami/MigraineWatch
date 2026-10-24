package com.radami.migrainewatch.domain

import java.time.Instant

/**
 * A warning the user has already been sent: the event's window and direction, which is all
 * deduplication matches on. Not an [AlertWindow], since a delivered warning keeps no delta, and
 * deliberately without the threshold it was sent at, so a sensitivity change can't make an
 * already announced event look new.
 */
data class DeliveredWarning(
    val start: Instant,
    val end: Instant,
    val direction: PressureDirection
)
