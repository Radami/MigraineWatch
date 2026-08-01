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
     * No destructive fallback, in any build type.
     *
     * A debug-only fallback would quietly absorb a missing migration on the one device that
     * would otherwise catch it, and the first time anyone found out would be a crash on a
     * user's phone. Without it, forgetting a migration breaks the app immediately during
     * development, which is the cheapest possible place to learn.
     *
     * The cost is that a schema change now requires either a migration or clearing the app's
     * data by hand:
     *
     *     adb shell pm clear com.radami.migrainewatch
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
