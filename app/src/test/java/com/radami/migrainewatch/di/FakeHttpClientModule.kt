package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Serves generated weather to every test, unconditionally.
 *
 * The journey tests script a specific forecast and assert on the alerts it produces, so they
 * cannot run against the real Open-Meteo. Replacing the provider rather than reading
 * BuildConfig.USE_MOCK_DATA is what makes that independent of the build flag: a developer
 * pointing their own build at the live API should not turn the suite red.
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
