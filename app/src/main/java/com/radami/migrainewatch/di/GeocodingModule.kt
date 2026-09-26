package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.GeocodingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Named
import javax.inject.Singleton

/**
 * Separate from the pressure endpoints because city search is never served by
 * [com.radami.migrainewatch.data.remote.mock.MockDataInterceptor]; tests swap this whole
 * module for a fake instead.
 */
@Module
@InstallIn(SingletonComponent::class)
object GeocodingModule {

    @Provides
    @Singleton
    @Named("geocoding")
    fun provideGeocodingRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("https://geocoding-api.open-meteo.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun provideGeocodingApi(@Named("geocoding") retrofit: Retrofit): GeocodingApi =
        retrofit.create(GeocodingApi::class.java)
}
