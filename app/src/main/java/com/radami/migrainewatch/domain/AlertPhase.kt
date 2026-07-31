package com.radami.migrainewatch.domain

import java.time.Instant

/**
 * Where an event sits relative to now, which decides what a notification about it can honestly
 * claim.
 *
 * Kept explicit rather than left to fall out of a time comparison at each call site: the two
 * cases want different copy and different scheduling, and folding them together is what once
 * let a warning headed "Starts Monday 22:00" arrive on Wednesday afternoon.
 */
enum class AlertPhase {

    /** Not started yet, so there is still time to act on it. */
    AHEAD,

    /** Already running. Still worth knowing, but it is a heads-up, not a warning. */
    UNDERWAY;

    companion object {
        fun of(alert: AlertWindow, now: Instant): AlertPhase =
            if (alert.start.isAfter(now)) AHEAD else UNDERWAY
    }
}
