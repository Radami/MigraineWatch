package com.radami.migrainewatch.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards against silently losing a user's history: no build type has a destructive fallback,
 * so a missing migration crashes on launch. To add one: bump [DATABASE_VERSION], build once so
 * Room exports the schema, commit it, then add a `migrate(N, N+1)` case below.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    /** Pins the current schema; fails if an entity changes without bumping [DATABASE_VERSION]. */
    @Test
    fun currentSchemaIsExported() {
        helper.createDatabase(TEST_DB, DATABASE_VERSION).close()
    }

    /**
     * SQLite requires a default (0/epoch) for the new column, but a row left there would never
     * overlap anything again and every past warning would refire. Backfill is the actual point.
     */
    @Test
    fun migration2To3_backfillsEndDateTimeFromStart() {
        val startMillis = 1_780_000_000_000L

        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                "INSERT INTO notified_alerts " +
                    "(startDateTime, direction, thresholdHpa, notifiedDateTime) " +
                    "VALUES (?, ?, ?, ?)",
                arrayOf<Any>(startMillis, "drop", 6.0f, startMillis)
            )
        }

        // Also validates the migrated tables against the exported v3 schema.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT startDateTime, endDateTime FROM notified_alerts").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(startMillis, row.getLong(0))
            assertEquals(startMillis, row.getLong(1))
        }
    }
}
