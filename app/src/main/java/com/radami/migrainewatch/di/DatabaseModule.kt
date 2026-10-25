package com.radami.migrainewatch.di

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.DATABASE_NAME
import com.radami.migrainewatch.data.local.buildAppDatabase
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

    /**
     * The phone's own SQLite, through Room's driver API so the migrations shared with iOS run
     * here too. The framework build is the one every existing install's file was written by.
     */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        buildAppDatabase(
            Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME),
            AndroidSQLiteDriver()
        )

    @Provides
    fun providePressureReadingDao(db: AppDatabase): PressureReadingDao = db.pressureReadingDao()

    @Provides
    fun provideSymptomEntryDao(db: AppDatabase): SymptomEntryDao = db.symptomEntryDao()

    @Provides
    fun provideNotifiedAlertDao(db: AppDatabase): NotifiedAlertDao = db.notifiedAlertDao()
}
