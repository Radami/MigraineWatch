package com.example.migrainetracker.data.remote

import com.example.migrainetracker.data.remote.dto.OpenMeteoResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoApi {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "pressure_msl,surface_pressure",
        @Query("past_days") pastDays: Int = 30,
        @Query("forecast_days") forecastDays: Int = 7,
        @Query("timezone") timezone: String
    ): OpenMeteoResponse
}

interface OpenMeteoArchiveApi {
    @GET("v1/archive")
    suspend fun getArchive(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("hourly") hourly: String = "pressure_msl,surface_pressure",
        @Query("start_date") startDate: String,
        @Query("end_date") endDate: String,
        @Query("timezone") timezone: String
    ): OpenMeteoResponse
}
