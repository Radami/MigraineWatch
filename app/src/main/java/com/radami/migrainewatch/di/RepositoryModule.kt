package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.local.dao.NotifiedAlertDao
import com.radami.migrainewatch.data.local.dao.PressureReadingDao
import com.radami.migrainewatch.data.local.dao.SymptomEntryDao
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.OpenMeteoApi
import com.radami.migrainewatch.data.remote.OpenMeteoArchiveApi
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.AlertReconcileMonitor
import com.radami.migrainewatch.domain.AlertReconciler
import com.radami.migrainewatch.domain.PressureAlertUseCase
import com.radami.migrainewatch.domain.WorkManagerAlertQueue
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * Builds the shared module's repositories and use case. They carry no DI annotations, since
 * the same classes are wired by hand on iOS, so Hilt learns how to make them here.
 */
@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun providePressureRepository(
        dao: PressureReadingDao,
        forecastApi: OpenMeteoApi,
        archiveApi: OpenMeteoArchiveApi,
        preferences: UserPreferences,
        @ApplicationScope scope: CoroutineScope
    ): PressureRepository = PressureRepository(dao, forecastApi, archiveApi, preferences, scope)

    @Provides
    @Singleton
    fun provideSymptomRepository(dao: SymptomEntryDao): SymptomRepository = SymptomRepository(dao)

    @Provides
    @Singleton
    fun providePressureAlertUseCase(
        pressureRepository: PressureRepository,
        preferences: UserPreferences
    ): PressureAlertUseCase = PressureAlertUseCase(pressureRepository, preferences)

    @Provides
    @Singleton
    fun provideAlertReconciler(
        queue: WorkManagerAlertQueue,
        alertUseCase: PressureAlertUseCase,
        notifiedAlertDao: NotifiedAlertDao,
        preferences: UserPreferences
    ): AlertReconciler = AlertReconciler(queue, alertUseCase, notifiedAlertDao, preferences)

    @Provides
    @Singleton
    fun provideAlertReconcileMonitor(
        pressureRepository: PressureRepository,
        preferences: UserPreferences,
        alertReconciler: AlertReconciler,
        @ApplicationScope scope: CoroutineScope
    ): AlertReconcileMonitor = AlertReconcileMonitor(pressureRepository, preferences, alertReconciler, scope)
}
