package com.radami.migrainewatch.data.remote.mock

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Intercepts calls to Open-Meteo and returns a generated realistic dataset.
 */
class MockDataInterceptor : Interceptor {

    /** Named for what the forecast contains, since that is the only thing a caller picks on. */
    enum class Scenario {
        THREE_EVENTS, // A 12 hPa drop, its 9 hPa recovery, then a 7 hPa drop
        FOUR_EVENTS,  // The same again with a fourth on the end: one more than the palette holds
        TWO_EVENTS,   // A 9 hPa drop and its 9 hPa recovery
        NO_EVENTS     // Flat
    }

    companion object {
        /** Which forecast shape is served; changed by tests and by DebugAlertReceiver over adb. */
        var currentScenario: Scenario = Scenario.THREE_EVENTS

        /** 30 days of history and the 7-day forecast Open-Meteo returns, hour by hour. */
        private const val FIRST_HOUR = -720
        private const val LAST_HOUR = 168

        /**
         * 3 back-to-back events (12/9/7 hPa) sized so each sensitivity preset shows a
         * different count: High 3, Medium 2, Low 1. No noise, so the deltas stay exact.
         */
        private val THREE_EVENT_CURVE = listOf(
            Anchor(FIRST_HOUR, 0f),
            // Two older excursions, outside chart range: history filler only.
            Anchor(-250, 0f), Anchor(-226, -15f), Anchor(-224, -15f), Anchor(-200, 0f),
            Anchor(-160, 0f), Anchor(-136, -15f), Anchor(-134, -15f), Anchor(-110, 0f),
            // Gentle climb into the peak, not a flat plateau, so the drop dates correctly.
            Anchor(-36, -0.8f), Anchor(-12, 0f),
            Anchor(8, -12f), Anchor(12, -12f),
            Anchor(32, -3f), Anchor(36, -3f),
            Anchor(56, -10f),
            Anchor(LAST_HOUR, -10f)
        )

        /**
         * 4 back-to-back events, one more than the Alerts card's 3-item palette can list.
         * Every delta is 12+ hPa so all four show at every sensitivity, and all sit
         * within the widest chart range regardless of time of day.
         */
        private val FOUR_EVENT_CURVE = listOf(
            Anchor(FIRST_HOUR, 0f),
            Anchor(-36, -0.8f), Anchor(-12, 0f),
            Anchor(8, -12f), Anchor(12, -12f),
            Anchor(32, 1f), Anchor(36, 1f),
            Anchor(56, -13f), Anchor(60, -13f),
            Anchor(80, -1f), Anchor(84, -1f),
            Anchor(LAST_HOUR, -1f)
        )

        /**
         * A 9 hPa drop and its 9 hPa recovery, each over 20h. 9 sits 1 hPa clear of both
         * neighboring presets, so it reliably shows at High/Medium and never at Low.
         */
        private val TWO_EVENT_CURVE = listOf(
            Anchor(FIRST_HOUR, 0f),
            Anchor(-21, -0.8f), Anchor(3, 0f),
            Anchor(23, -9f), Anchor(27, -9f),
            Anchor(47, 0f),
            Anchor(LAST_HOUR, 0f)
        )

        /** Nothing happens. The empty case: no alerts to raise, and any pending ones cancel. */
        private val NO_EVENT_CURVE = listOf(Anchor(FIRST_HOUR, 0f), Anchor(LAST_HOUR, 0f))

        private fun curveFor(scenario: Scenario): List<Anchor> = when (scenario) {
            Scenario.THREE_EVENTS -> THREE_EVENT_CURVE
            Scenario.FOUR_EVENTS -> FOUR_EVENT_CURVE
            Scenario.TWO_EVENTS -> TWO_EVENT_CURVE
            Scenario.NO_EVENTS -> NO_EVENT_CURVE
        }

        /** The curve's value at [hour], eased between anchors with smoothstep (corners rounded, deltas exact). */
        private fun List<Anchor>.offsetAt(hour: Int): Float {
            if (hour <= first().hour) return first().offsetHpa
            if (hour >= last().hour) return last().offsetHpa

            val endIndex = indexOfFirst { it.hour >= hour }
            val end = this[endIndex]
            val start = this[endIndex - 1]
            if (end.hour == start.hour) return end.offsetHpa

            val progress = (hour - start.hour).toFloat() / (end.hour - start.hour)
            return start.offsetHpa + (end.offsetHpa - start.offsetHpa) * smoothStep(progress)
        }

        /** 3t² − 2t³: runs 0 to 1 with zero slope at both ends. */
        private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)
    }

    /** One point on a scenario's curve: [offsetHpa] from base pressure, [hour] hours from now. */
    private data class Anchor(val hour: Int, val offsetHpa: Float)

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
        // ROOT for the same reason as the real request it stands in for: this JSON is parsed
        // back by PressureRepository, so the digits have to stay Latin whatever the device is.
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.ROOT)

        // Use lat/lon to seed the base pressure so different locations have different values
        // This ensures Scenario A (Nervous Traveler) can verify data changes.
        val locationSeed = (lat + lon).toFloat()
        val basePressure = 1013f + (locationSeed % 5f)

        // No noise layered on top, so every scenario's alerts are exactly the size its curve
        // describes, everywhere. A wobble could push an event past a preset boundary.
        val curve = curveFor(currentScenario)

        for (hour in FIRST_HOUR..LAST_HOUR) {
            val time = now.plusHours(hour.toLong())
            times.add(time.format(formatter))

            pressures.add(basePressure + curve.offsetAt(hour))
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
