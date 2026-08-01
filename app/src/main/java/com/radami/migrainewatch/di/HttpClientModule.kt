package com.radami.migrainewatch.di

import com.radami.migrainewatch.BuildConfig
import com.radami.migrainewatch.data.remote.mock.MockDataInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Singleton

/**
 * The HTTP client, kept apart from [NetworkModule] so tests can swap it without also having to
 * restate the Json, Retrofit and API providers that would come with replacing a larger module.
 */
@Module
@InstallIn(SingletonComponent::class)
object HttpClientModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .apply {
            // Gated on its own flag rather than on DEBUG: a debug build pointed at the real
            // Open-Meteo is the ordinary way to work, and mock weather is the exception.
            if (BuildConfig.USE_MOCK_DATA) {
                addInterceptor(MockDataInterceptor())
            }

            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()
}
