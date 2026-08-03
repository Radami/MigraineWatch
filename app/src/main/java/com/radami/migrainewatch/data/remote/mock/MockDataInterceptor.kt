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
        TWO_EVENTS,   // A 9 hPa drop and its 9 hPa recovery
        NO_EVENTS     // Flat
    }

    companion object {
        /**
         * Which forecast shape is served. [Scenario.THREE_EVENTS] covers every alert
         * sensitivity on its own, so this is only changed by the tests and by
         * [com.radami.migrainewatch.debug.DebugAlertReceiver] over adb.
         */
        var currentScenario: Scenario = Scenario.THREE_EVENTS

        /** 30 days of history and the 7-day forecast Open-Meteo returns, hour by hour. */
        private const val FIRST_HOUR = -720
        private const val LAST_HOUR = 168

        /**
         * Three back-to-back events fill the alert detail chart, which spans 24 h behind to
         * 60 h ahead: a 12 hPa drop already under way, the 9 hPa recovery from it, then a
         * 7 hPa drop. Each event's turning point is where the next one starts,
         * so the detector reports three separate alerts and the sensitivity presets peel them
         * off one at a time — High (6) shows all three, Medium (8) two, Low (10) one.
         *
         * Those deltas are exactly 12, 9 and 7 because no wobble is laid over them. An event
         * sized 1 hPa clear of a preset boundary with noise laid over it can only be trusted
         * by simulating every phase of the noise, which is what the previous shape needed.
         */
        private val THREE_EVENT_CURVE = listOf(
            Anchor(FIRST_HOUR, 0f),
            // Two completed excursions inside the past month, for the "Last 3 events" card.
            Anchor(-250, 0f), Anchor(-226, -15f), Anchor(-224, -15f), Anchor(-200, 0f),
            Anchor(-160, 0f), Anchor(-136, -15f), Anchor(-134, -15f), Anchor(-110, 0f),
            // Pressure climbs gently into the peak rather than arriving along a flat plateau.
            // An event is pinned to the first reading holding its extreme value, so a dead
            // flat approach would date the drop from the far end of the plateau.
            Anchor(-36, -0.8f), Anchor(-12, 0f),
            Anchor(8, -12f), Anchor(12, -12f),
            Anchor(32, -3f), Anchor(36, -3f),
            Anchor(56, -10f),
            Anchor(LAST_HOUR, -10f)
        )

        /**
         * A storm arriving and clearing: a 9 hPa drop and the 9 hPa recovery behind it, each
         * over 20 h so a 24 h detection window sees the whole of one.
         *
         * 9 sits a clear 1 hPa inside both neighbouring presets, which is what makes the two
         * properties the tests lean on hold everywhere rather than at one location: the events
         * always show at High and Medium, and never at Low. `UserJourneyTest.scenarioC` walks
         * the sensitivity down until the banner goes away, so "never at Low" has to be a
         * guarantee, not a calibration.
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
            Scenario.TWO_EVENTS -> TWO_EVENT_CURVE
            Scenario.NO_EVENTS -> NO_EVENT_CURVE
        }

        /**
         * The curve's value at [hour], eased between the anchors on either side. Smoothstep
         * rather than a straight line: it still passes exactly through every anchor, so the
         * deltas the table describes survive, but the corners are rounded off.
         */
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

    /**
     * One point on a scenario's pressure curve: [offsetHpa] away from the base pressure,
     * [hour] hours from now. Defining a scenario as a table of these keeps its shape readable
     * — and its alert deltas exact — where ramp arithmetic spread over a when-block was
     * neither.
     */
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

        // No noise is layered on top: every scenario's alerts are exactly the size its curve
        // describes, at every location. Sizing an event 1 hPa clear of a sensitivity preset
        // and then adding a wobble that can reach it means the scenario only behaves where it
        // happened to be checked.
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
