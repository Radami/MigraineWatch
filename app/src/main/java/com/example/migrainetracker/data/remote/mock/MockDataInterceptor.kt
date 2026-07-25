package com.example.migrainetracker.data.remote.mock

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sin

/**
 * Intercepts calls to Open-Meteo and returns a generated realistic dataset.
 */
class MockDataInterceptor : Interceptor {

    enum class Scenario {
        NORMAL,
        STORM, // Forced 10.3hPa drop
        CALM   // No major pressure changes
    }

    companion object {
        var currentScenario: Scenario = Scenario.NORMAL
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        // Only mock the pressure endpoints; geocoding-api.open-meteo.com must hit the real network.
        val isMeteo = url.host == "api.open-meteo.com" || url.host == "archive-api.open-meteo.com"

        if (isMeteo) {
            val lat = url.queryParameter("latitude")?.toDoubleOrNull() ?: 0.0
            val lon = url.queryParameter("longitude")?.toDoubleOrNull() ?: 0.0
            val json = generateMockJson(lat, lon)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }

        return chain.proceed(request)
    }

    private fun generateMockJson(lat: Double, lon: Double): String {
        val now = LocalDateTime.now()
        val systemTimezone = ZoneId.systemDefault().id
        val times = mutableListOf<String>()
        val pressures = mutableListOf<Float>()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

        // Use lat/lon to seed the base pressure so different locations have different values
        // This ensures Scenario A (Nervous Traveler) can verify data changes.
        val locationSeed = (lat + lon).toFloat()
        val basePressure = 1013f + (locationSeed % 5f)

        // NORMAL's events are sized to land between the alert sensitivity presets, and only
        // 2 hPa separates one preset from the next. Its wobble is therefore kept small enough
        // that no phase of the sine can push an event across a neighbouring threshold; the
        // other scenarios keep the livelier wobble.
        val varianceAmplitude = when (currentScenario) {
            Scenario.NORMAL -> 0.35f
            Scenario.STORM, Scenario.CALM -> 2f
        }

        // Generate 30 days past + 7 days future (888 data points)
        for (i in -720..168) {
            val time = now.plusHours(i.toLong())
            times.add(time.format(formatter))

            val variance = varianceAmplitude * sin(i / 10.0 + locationSeed).toFloat()

            val eventOffset: Float = when (currentScenario) {
                Scenario.STORM -> {
                    // Forced 10.3hPa drop for Scenario B/C testing
                    when {
                        i < 3 -> 0f
                        i <= 39 -> {
                            val progress = (i - 3) / 36f
                            -(progress * 10.3f)
                        }
                        i <= 42 -> -10.3f
                        i <= 66 -> {
                            val progress = (i - 42) / 24f
                            -10.3f + (progress * 12f)
                        }
                        else -> 1.7f
                    }
                }
                Scenario.CALM -> 0f
                Scenario.NORMAL -> {
                    // Three events in the forecast window, sized so the alert list shrinks as
                    // the user lowers their sensitivity: 12 hPa, then 9, then 7 over 24 h.
                    // High (6 hPa) reports all three, Medium (8) the first two, Low (10) only
                    // the first. Directions alternate so the detector keeps them separate, and
                    // the first drop starts 12 h in the past so the app opens mid-event.
                    //
                    // Ramps run 20 h with 28 h of calm between them: no 24 h detection window
                    // can span two events, which would merge them into one alert.
                    //
                    // Two completed drop-and-recovery excursions further in the past (ending
                    // ~4.5 and ~8 days ago) populate the "Last 3 events" card; each one
                    // produces a drop event and a rise event.
                    when {
                        i < -250 -> 0f
                        i <= -226 -> -15f * (i + 250) / 24f
                        i <= -224 -> -15f
                        i <= -200 -> -15f + 15f * (i + 224) / 24f
                        i < -160 -> 0f
                        i <= -136 -> -15f * (i + 160) / 24f
                        i <= -134 -> -15f
                        i <= -110 -> -15f + 15f * (i + 134) / 24f
                        i < -12 -> 0f
                        i <= 8 -> -12f * (i + 12) / 20f
                        i <= 36 -> -12f
                        i <= 56 -> -12f + 9f * (i - 36) / 20f
                        i <= 84 -> -3f
                        i <= 104 -> -3f - 7f * (i - 84) / 20f
                        else -> -10f
                    }
                }
            }
            
            pressures.add(basePressure + variance + eventOffset)
        }

        return """
            {
                "latitude": $lat,
                "longitude": $lon,
                "timezone": "$systemTimezone",
                "hourly": {
                    "time": ${times.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},
                    "pressure_msl": ${pressures.joinToString(prefix = "[", postfix = "]", separator = ",")},
                    "surface_pressure": ${pressures.joinToString(prefix = "[", postfix = "]", separator = ",")}
                }
            }
        """.trimIndent()
    }
}
