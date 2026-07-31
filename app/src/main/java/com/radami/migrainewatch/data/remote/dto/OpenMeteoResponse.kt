package com.radami.migrainewatch.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpenMeteoResponse(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val hourly: HourlyData
)

@Serializable
data class HourlyData(
    val time: List<String>,
    @SerialName("pressure_msl") val pressureMsl: List<Float?>,
    @SerialName("surface_pressure") val surfacePressure: List<Float?>
)
