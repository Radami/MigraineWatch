package com.radami.migrainewatch.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `notified_alerts.endDateTime`, so a delivered warning records the whole event window
 * rather than only where it began.
 *
 * Matching used to compare start times within a six-hour tolerance, which collapsed two
 * genuinely separate events that happened to begin close together. Overlap needs the end.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // SQLite can only add a NOT NULL column with a default, hence the literal here and the
        // matching @ColumnInfo(defaultValue) on the entity — the two have to agree or Room
        // rejects the migrated schema.
        db.execSQL(
            "ALTER TABLE notified_alerts ADD COLUMN endDateTime INTEGER NOT NULL DEFAULT 0"
        )

        // Existing rows never stored an end. Leaving them on the default would place their
        // window at the epoch, before their own start, and nothing would overlap them again —
        // so every past warning would re-fire once. Collapsing the window onto the start is
        // the honest approximation: it still overlaps any forecast covering that moment.
        db.execSQL("UPDATE notified_alerts SET endDateTime = startDateTime")
    }
}
