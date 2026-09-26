package com.radami.migrainewatch.domain

import java.time.Instant

/**
 * Where an event sits relative to now, deciding what a notification can honestly claim. Kept
 * explicit rather than a per-call-site time comparison, since the two cases need different
 * copy and scheduling.
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
