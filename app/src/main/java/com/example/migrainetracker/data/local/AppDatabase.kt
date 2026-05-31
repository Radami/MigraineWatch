package com.example.migrainetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.migrainetracker.data.local.dao.PressureReadingDao
import com.example.migrainetracker.data.local.dao.SymptomEntryDao
import com.example.migrainetracker.data.model.PressureReading
import com.example.migrainetracker.data.model.SymptomEntry

@Database(
    entities = [PressureReading::class, SymptomEntry::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pressureReadingDao(): PressureReadingDao
    abstract fun symptomEntryDao(): SymptomEntryDao
}
