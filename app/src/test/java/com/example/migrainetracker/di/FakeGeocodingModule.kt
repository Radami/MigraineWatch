package com.example.migrainetracker.di

import com.example.migrainetracker.data.remote.GeocodingApi
import com.example.migrainetracker.data.remote.dto.GeocodingResponse
import com.example.migrainetracker.data.remote.dto.GeocodingResult
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/**
 * Serves city search from a fixed table so journey tests never touch the real geocoding
 * service, which [GeocodingModule] deliberately leaves unmocked.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GeocodingModule::class])
object FakeGeocodingModule {

    private val KNOWN_CITIES = listOf(
        GeocodingResult(
            name = "Zurich",
            latitude = 47.3769,
            longitude = 8.5417,
            country = "Switzerland",
            timezone = "Europe/Zurich"
        ),
        GeocodingResult(
            name = "Berlin",
            latitude = 52.52,
            longitude = 13.41,
            country = "Germany",
            timezone = "Europe/Berlin"
        )
    )

    @Provides
    @Singleton
    fun provideGeocodingApi(): GeocodingApi = object : GeocodingApi {
        override suspend fun search(
            name: String,
            count: Int,
            language: String,
            format: String
        ): GeocodingResponse {
            val matches = KNOWN_CITIES.filter { it.name.startsWith(name, ignoreCase = true) }
            return GeocodingResponse(results = matches.take(count))
        }
    }
}
