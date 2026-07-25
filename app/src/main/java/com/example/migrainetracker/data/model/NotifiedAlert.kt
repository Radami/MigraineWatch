package com.example.migrainetracker.data.model

import androidx.room.Entity
import java.time.Instant

/**
 * A pressure event the user has already been notified about.
 *
 * Kept so an event that reappears in every hourly forecast is only announced once. Matching is
 * deliberately fuzzy (see AlertNotificationDecider) because a forecast refresh nudges an
 * event's start by an hour or two without it being a different event.
 */
@Entity(tableName = "notified_alerts", primaryKeys = ["startDateTime", "direction"])
data class NotifiedAlert(
    val startDateTime: Instant,
    val direction: String,
    /** The sensitivity in force when it was sent. Diagnostic only — see the decider. */
    val thresholdHpa: Float,
    val notifiedDateTime: Instant
)
