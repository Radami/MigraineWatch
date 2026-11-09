package com.radami.migrainewatch.data.remote

import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse

interface OpenMeteoApi {
    suspend fun getForecast(
        latitude: Double,
        longitude: Double,
        hourly: String = HOURLY_PRESSURE,
        pastDays: Int = 30,
        forecastDays: Int = 7,
        timezone: String
    ): OpenMeteoResponse
}

interface OpenMeteoArchiveApi {
    suspend fun getArchive(
        latitude: Double,
        longitude: Double,
        hourly: String = HOURLY_PRESSURE,
        startDate: String,
        endDate: String,
        timezone: String
    ): OpenMeteoResponse
}

/** The hourly series both endpoints are asked for. */
const val HOURLY_PRESSURE = "pressure_msl,surface_pressure"
