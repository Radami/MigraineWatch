package com.radami.migrainewatch.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import java.time.Instant

/**
 * A pressure event the user has already been notified about.
 *
 * Kept so an event that reappears in every hourly forecast is only announced once. The whole
 * window is stored, not just its start, because a delivered warning is matched to a forecast
 * by overlap — see AlertNotificationDecider.
 */
@Entity(tableName = "notified_alerts", primaryKeys = ["startDateTime", "direction"])
data class NotifiedAlert(
    val startDateTime: Instant,
    /**
     * The default exists only so the column Room expects matches the one MIGRATION_2_3 adds;
     * every insert supplies a real value. Rows predating that migration are backfilled there
     * rather than left on it.
     */
    @ColumnInfo(defaultValue = "0")
    val endDateTime: Instant,
    /**
     * A `PressureDirection.wireName`. Stored as the string it has always been so rows written
     * by an earlier install keep matching, and read back through `ofWireName` rather than
     * being trusted as a constant name.
     */
    val direction: String,
    /** The sensitivity in force when it was sent. Diagnostic only — see the decider. */
    val thresholdHpa: Float,
    val notifiedDateTime: Instant
)
