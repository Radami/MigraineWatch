package com.radami.migrainewatch.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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

@Database(
    entities = [PressureReading::class, SymptomEntry::class, NotifiedAlert::class],
    version = DATABASE_VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressureReadingDao(): PressureReadingDao
    abstract fun symptomEntryDao(): SymptomEntryDao
    abstract fun notifiedAlertDao(): NotifiedAlertDao
}
