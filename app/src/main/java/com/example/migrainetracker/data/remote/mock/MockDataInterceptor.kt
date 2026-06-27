package com.example.migrainetracker.data.remote.mock

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import com.example.migrainetracker.data.preferences.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Intercepts calls to Open-Meteo and returns a generated realistic dataset.
 */
class MockDataInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()

        if (url.contains("open-meteo.com")) {
            val json = generateMockJson()
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

    private fun generateMockJson(): String {
        val now = LocalDateTime.now()
        val times = mutableListOf<String>()
        val pressures = mutableListOf<Float>()
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

        // Generate 30 days past + 7 days future (888 data points)
        for (i in -720..168) {
            val time = now.plusHours(i.toLong())
            times.add(time.format(formatter))

            // Base pressure + sine wave for natural variation
            val base = 1013f
            val variance = 2f * sin(i / 10.0).toFloat()

            // i=0 is 'now'
            // Event 1: 10hPa drop starting at i=3 over 24h (ends at i=27)
            // Pause: 3 hours at the low point (i=27 to i=30)
            // Event 2: 12hPa rise starting at i=30 over 24h (ends at i=54)
            val eventOffset: Float = when {
                i < 3 -> 0f
                i <= 27 -> {
                    val progress = (i - 3) / 24f
                    -(progress * 10f)
                }
                i <= 39 -> -10f
                i <= 63 -> {
                    val progress = (i - 30) / 24f
                    -10f + (progress * 12f)
                }
                else -> 2f
            }

            pressures.add(base + variance + eventOffset)
        }

        // Return raw JSON matching the OpenMeteoResponse format
        return """
            {
                "latitude": 52.52,
                "longitude": 13.41,
                "timezone": "UTC",
                "hourly": {
                    "time": ${times.joinToString(prefix = "[\"", postfix = "\"]", separator = "\",\"")},
                    "pressure_msl": ${pressures.joinToString(prefix = "[", postfix = "]", separator = ",")},
                    "surface_pressure": ${pressures.joinToString(prefix = "[", postfix = "]", separator = ",")}
                }
            }
        """.trimIndent()
    }
}
