package com.radami.migrainewatch.di

import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.KtorGeocodingApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
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
    fun provideGeocodingApi(client: HttpClient): GeocodingApi = KtorGeocodingApi(client)
}
