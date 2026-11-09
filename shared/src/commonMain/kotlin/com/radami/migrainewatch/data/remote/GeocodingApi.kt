package com.radami.migrainewatch.data.remote

import com.radami.migrainewatch.data.remote.dto.GeocodingResponse

interface GeocodingApi {
    suspend fun search(
        name: String,
        count: Int = 5,
        language: String = "en",
        format: String = "json"
    ): GeocodingResponse
}
