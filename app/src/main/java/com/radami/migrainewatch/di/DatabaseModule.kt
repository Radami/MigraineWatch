package com.radami.migrainewatch.di

import android.content.Context
import androidx.room.Room
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.MIGRATION_2_3
import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.local.dao.SymptomEntryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "migraine_watch.db"

    /**
     * No destructive fallback, in any build type: a missing migration should crash in
     * development, not silently wipe data on a user's phone. Clear local data by hand instead
     * with `adb shell pm clear com.radami.migrainewatch`.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_2_3)
            .build()

    @Provides
    fun providePressureReadingDao(db: AppDatabase): PressureReadingDao = db.pressureReadingDao()

    @Provides
    fun provideSymptomEntryDao(db: AppDatabase): SymptomEntryDao = db.symptomEntryDao()

    @Provides
    fun provideNotifiedAlertDao(db: AppDatabase): NotifiedAlertDao = db.notifiedAlertDao()
}
