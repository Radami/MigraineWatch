package com.example.migrainetracker.di

import android.content.Context
import androidx.room.Room
import com.example.migrainetracker.data.local.AppDatabase
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
            .build()

    @Provides
    fun providePressureReadingDao(db: AppDatabase): PressureReadingDao = db.pressureReadingDao()

    @Provides
    fun provideSymptomEntryDao(db: AppDatabase): SymptomEntryDao = db.symptomEntryDao()
}
