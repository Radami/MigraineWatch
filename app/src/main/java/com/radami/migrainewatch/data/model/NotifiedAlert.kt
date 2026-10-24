package com.radami.migrainewatch.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.radami.migrainewatch.domain.DeliveredWarning
import com.radami.migrainewatch.domain.PressureDirection
import java.time.Instant
import kotlin.time.toKotlinInstant

/**
 * A pressure event the user has already been notified about.
 * Kept so a repeating hourly forecast only gets announced once; matched to a
 * forecast by overlap of the whole window, see AlertNotificationDecider.
 */
@Entity(tableName = "notified_alerts", primaryKeys = ["startDateTime", "direction"])
data class NotifiedAlert(
    val startDateTime: Instant,
    /** Default matches MIGRATION_2_3's column default; every insert supplies a real value. */
    @ColumnInfo(defaultValue = "0")
    val endDateTime: Instant,
    /** A `PressureDirection.wireName`; read back via `ofWireName`, not trusted as a constant name. */
    val direction: String,
    /** The sensitivity in force when it was sent. Diagnostic only — see the decider. */
    val thresholdHpa: Float,
    val notifiedDateTime: Instant
)

/**
 * The warning this row records, or null when its direction is one this build can't read.
 * Such a row then matches no event: re-announcing is safer than silencing the wrong one.
 */
fun NotifiedAlert.toDeliveredWarning(): DeliveredWarning? {
    val direction = PressureDirection.ofWireName(direction) ?: return null

    return DeliveredWarning(
        start = startDateTime.toKotlinInstant(),
        end = endDateTime.toKotlinInstant(),
        direction = direction
    )
}
