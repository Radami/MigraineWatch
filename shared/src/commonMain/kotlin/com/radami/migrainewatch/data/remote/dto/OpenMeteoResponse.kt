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
    /**
     * Each hour's start, in seconds since the epoch, as asked for with `timeformat=unixtime`.
     * Not local clock times: the hour repeated when clocks go back would read the same twice,
     * and one of the two readings would overwrite the other.
     */
    val time: List<Long>,
    @SerialName("pressure_msl") val pressureMsl: List<Float?>,
    @SerialName("surface_pressure") val surfacePressure: List<Float?>
)
