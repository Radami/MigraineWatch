package com.example.migrainetracker.di

import android.content.Context
import androidx.room.Room
import com.example.migrainetracker.data.local.AppDatabase
import com.example.migrainetracker.data.local.dao.NotifiedAlertDao
import com.example.migrainetracker.data.local.dao.PressureReadingDao
import com.example.migrainetracker.data.local.dao.SymptomEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "migraine_tracker.db")
            // The app is unreleased, so a schema change wipes the database instead of
            // migrating it. Add real migrations at the point there is a build in someone
            // else's hands: the symptom log is the one thing in here they cannot recreate.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun providePressureReadingDao(db: AppDatabase): PressureReadingDao = db.pressureReadingDao()

    @Provides
    fun provideSymptomEntryDao(db: AppDatabase): SymptomEntryDao = db.symptomEntryDao()

    @Provides
    fun provideNotifiedAlertDao(db: AppDatabase): NotifiedAlertDao = db.notifiedAlertDao()
}
