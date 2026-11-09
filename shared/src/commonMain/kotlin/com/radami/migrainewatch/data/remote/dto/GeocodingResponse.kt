package com.radami.migrainewatch.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class GeocodingResponse(
    val results: List<GeocodingResult>? = null
)

@Serializable
data class GeocodingResult(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val timezone: String? = null,
    val admin1: String? = null
) {
    val displayName: String get() = buildString {
        append(name)
        admin1?.let { append(", $it") }
        country?.let { append(", $it") }
    }
}
