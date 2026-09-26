package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Serves generated weather to every test, unconditionally. Journey tests script a specific
 * forecast, so they can't hit the real Open-Meteo API. Replacing the provider (not reading
 * BuildConfig.USE_MOCK_DATA) keeps this independent of a developer's own build flag.
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
