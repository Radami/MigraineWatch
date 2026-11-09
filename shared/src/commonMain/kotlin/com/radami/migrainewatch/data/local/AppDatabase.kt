package com.radami.migrainewatch.data.local

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteDriver
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.local.dao.SymptomEntryDao
import com.radami.migrainewatch.data.model.NotifiedAlert
import com.radami.migrainewatch.data.model.PressureReading
import com.radami.migrainewatch.data.model.SymptomEntry

/**
 * Bump on every schema change, and add both the matching migration and its test. The exported
 * schema for the previous version is what makes that possible, so it is committed alongside.
 */
const val DATABASE_VERSION = 3

/** The file existing installs keep their data in. */
const val DATABASE_NAME = "migraine_watch.db"

@Database(
    entities = [PressureReading::class, SymptomEntry::class, NotifiedAlert::class],
    version = DATABASE_VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressureReadingDao(): PressureReadingDao
    abstract fun symptomEntryDao(): SymptomEntryDao
    abstract fun notifiedAlertDao(): NotifiedAlertDao
}

/** Room generates the actual for each platform. */
@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

/**
 * Finishes a platform's [builder] the same way everywhere: the migrations, and the SQLite
 * [driver] the platform chose.
 *
 * No destructive fallback, in any build type: a missing migration should crash in
 * development, not silently wipe data on a user's phone. Clear local data by hand instead
 * with `adb shell pm clear com.radami.migrainewatch`.
 */
fun buildAppDatabase(builder: RoomDatabase.Builder<AppDatabase>, driver: SQLiteDriver): AppDatabase =
    builder
        .addMigrations(MIGRATION_2_3)
        .setDriver(driver)
        .build()
