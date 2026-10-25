package com.radami.migrainewatch.data.local

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds `notified_alerts.endDateTime` so a warning records the whole event window, not
 * just its start. Old start-only matching with a tolerance could conflate separate events.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        // Default 0 here must match @ColumnInfo(defaultValue) on the entity, or Room
        // rejects the migrated schema.
        connection.execSQL(
            "ALTER TABLE notified_alerts ADD COLUMN endDateTime INTEGER NOT NULL DEFAULT 0"
        )

        // Existing rows have no end date. Collapse the window onto the start rather than
        // leaving the epoch default, which would make every past warning re-fire once.
        connection.execSQL("UPDATE notified_alerts SET endDateTime = startDateTime")
    }
}
