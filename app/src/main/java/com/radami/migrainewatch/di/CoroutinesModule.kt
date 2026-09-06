package com.radami.migrainewatch.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * A scope that outlives every screen, for work whose result is shared between them.
 *
 * The distinction that matters is cancellation. Work launched in a `viewModelScope` dies with
 * the screen that started it, which is right for anything only that screen wanted. It is wrong
 * for work several callers are waiting on: the first screen to close would take the result
 * away from the others.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object CoroutinesModule {

    /**
     * [SupervisorJob] so one failed piece of shared work does not cancel the scope and take
     * every later use of it down with it. Nothing cancels this scope: it lives as long as the
     * process, which is the point of it.
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
