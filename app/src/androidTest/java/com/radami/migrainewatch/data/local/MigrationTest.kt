package com.radami.migrainewatch.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /** Through the same driver the app uses, so migrations run exactly as they do there. */
    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath(TEST_DB),
        driver = AndroidSQLiteDriver(),
        databaseClass = AppDatabase::class
    )

    /** The driver-based helper leaves its file behind, so each test starts from none. */
    @Before
    fun startFromNoDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DB)
    }

    /** Pins the current schema; fails if an entity changes without bumping [DATABASE_VERSION]. */
    @Test
    fun currentSchemaIsExported() {
        helper.createDatabase(DATABASE_VERSION).close()
    }

    /**
     * SQLite requires a default (0/epoch) for the new column, but a row left there would never
     * overlap anything again and every past warning would refire. Backfill is the actual point.
     */
    @Test
    fun migration2To3_backfillsEndDateTimeFromStart() {
        val startMillis = 1_780_000_000_000L

        helper.createDatabase(2).use { connection ->
            connection.prepare(
                "INSERT INTO notified_alerts " +
                    "(startDateTime, direction, thresholdHpa, notifiedDateTime) " +
                    "VALUES (?, ?, ?, ?)"
            ).use { insert ->
                insert.bindLong(1, startMillis)
                insert.bindText(2, "drop")
                insert.bindDouble(3, 6.0)
                insert.bindLong(4, startMillis)
                insert.step()
            }
        }

        // Also validates the migrated tables against the exported v3 schema.
        helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).use { migrated ->
            migrated.prepare("SELECT startDateTime, endDateTime FROM notified_alerts").use { row ->
                assertTrue(row.step())
                assertEquals(startMillis, row.getLong(0))
                assertEquals(startMillis, row.getLong(1))
            }
        }
    }
}
