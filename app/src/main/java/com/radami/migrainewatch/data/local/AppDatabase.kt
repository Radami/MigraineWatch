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

@Database(
    entities = [PressureReading::class, SymptomEntry::class, NotifiedAlert::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressureReadingDao(): PressureReadingDao
    abstract fun symptomEntryDao(): SymptomEntryDao
    abstract fun notifiedAlertDao(): NotifiedAlertDao
}
