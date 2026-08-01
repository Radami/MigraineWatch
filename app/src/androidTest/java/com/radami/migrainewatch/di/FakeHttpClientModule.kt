package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * The instrumented counterpart of the unit tests' fake client. NavigationTest picks a forecast
 * through [MockDataInterceptor.currentScenario], so it needs the interceptor installed however
 * the build flag is set. Duplicated rather than shared because test and androidTest are
 * separate source sets.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [HttpClientModule::class])
object FakeHttpClientModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(MockDataInterceptor())
        .build()
}
