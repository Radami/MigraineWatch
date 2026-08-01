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
 * Guards the database against silently losing a user's history.
 *
 * No build type has a destructive fallback (see DatabaseModule), so a schema change without a
 * migration fails the same way everywhere — on a developer's phone first, long before a user's.
 * These tests are where it should be caught earlier still.
 *
 * Adding a migration means: bump [DATABASE_VERSION], build once so Room exports the new schema
 * into app/schemas, commit that JSON, then add a `migrate(N, N+1)` case below.
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

    /**
     * Pins the current schema. Fails as soon as an entity changes without [DATABASE_VERSION]
     * being bumped, which is the mistake that reaches a phone as a crash on launch.
     */
    @Test
    fun currentSchemaIsExported() {
        helper.createDatabase(TEST_DB, DATABASE_VERSION).close()
    }

    /**
     * The column is added with a default of 0 because SQLite demands one, but a row left on it
     * would sit at the epoch — before its own start — and never overlap anything again, so
     * every past warning would fire a second time. The backfill is the point of the migration.
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

        // Validates the migrated tables against the exported v3 schema as well as running it.
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        migrated.query("SELECT startDateTime, endDateTime FROM notified_alerts").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(startMillis, row.getLong(0))
            assertEquals(startMillis, row.getLong(1))
        }
    }
}
