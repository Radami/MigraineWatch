package com.radami.migrainewatch.data.remote

import com.radami.migrainewatch.data.remote.dto.GeocodingResponse
import com.radami.migrainewatch.data.remote.dto.HourlyData
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import com.radami.migrainewatch.di.GeocodingModule
import com.radami.migrainewatch.di.NetworkModule
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * Pins what goes over the wire and what comes back, through the production clients, with an
 * interceptor standing in for Open-Meteo. The repository turns any throw into a failed fetch,
 * so a client that returned an error response instead of throwing would pass it off as data.
 */
class OpenMeteoHttpTest {

    private companion object {
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SERVER_ERROR = 500

        const val LATITUDE = 27.7172
        const val LONGITUDE = 85.324
        const val ZONE = "Asia/Kathmandu"

        /** A forecast as Open-Meteo sends it, plus a field this app has never read. */
        const val FORECAST_BODY = """
            {
              "latitude": 27.75,
              "longitude": 85.25,
              "timezone": "Asia/Kathmandu",
              "generationtime_ms": 0.12,
              "hourly": {
                "time": ["2026-08-24T00:00", "2026-08-24T01:00"],
                "pressure_msl": [1013.2, null],
                "surface_pressure": [870.5, 870.1]
              }
            }
        """

        const val EMPTY_SEARCH_BODY = """{ "generationtime_ms": 0.3 }"""
    }

    /** Records every request and answers it with [status] and [body], or with [failure]. */
    private class FakeOpenMeteo : Interceptor {
        val requests = mutableListOf<Request>()
        var status = HTTP_OK
        var body = FORECAST_BODY
        var failure: IOException? = null

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            failure?.let { throw it }

            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("status $status")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private val server = FakeOpenMeteo()
    private val client = OkHttpClient.Builder().addInterceptor(server).build()
    private val json = NetworkModule.provideJson()

    private val forecastApi =
        NetworkModule.provideOpenMeteoApi(NetworkModule.provideForecastRetrofit(client, json))
    private val archiveApi =
        NetworkModule.provideOpenMeteoArchiveApi(NetworkModule.provideArchiveRetrofit(client, json))
    private val geocodingApi =
        GeocodingModule.provideGeocodingApi(GeocodingModule.provideGeocodingRetrofit(client, json))

    private fun fetchForecast() = runBlocking {
        forecastApi.getForecast(latitude = LATITUDE, longitude = LONGITUDE, timezone = ZONE)
    }

    /** Scheme, host and path, then every query parameter decoded, so encoding can't matter. */
    private fun HttpUrl.described(): Pair<String, Map<String, String?>> =
        "$scheme://$host$encodedPath" to queryParameterNames.associateWith { queryParameter(it) }

    @Test
    fun `the forecast is asked for with its established parameters`() {
        fetchForecast()

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://api.open-meteo.com/v1/forecast" to mapOf(
                "latitude" to "27.7172",
                "longitude" to "85.324",
                "hourly" to "pressure_msl,surface_pressure",
                "past_days" to "30",
                "forecast_days" to "7",
                "timezone" to ZONE
            ),
            request.url.described()
        )
    }

    @Test
    fun `the archive is asked for with its established parameters`() {
        runBlocking {
            archiveApi.getArchive(
                latitude = LATITUDE,
                longitude = LONGITUDE,
                startDate = "2026-06-01",
                endDate = "2026-07-25",
                timezone = ZONE
            )
        }

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://archive-api.open-meteo.com/v1/archive" to mapOf(
                "latitude" to "27.7172",
                "longitude" to "85.324",
                "hourly" to "pressure_msl,surface_pressure",
                "start_date" to "2026-06-01",
                "end_date" to "2026-07-25",
                "timezone" to ZONE
            ),
            request.url.described()
        )
    }

    @Test
    fun `a city search is asked for with its established parameters`() {
        server.body = EMPTY_SEARCH_BODY

        runBlocking { geocodingApi.search("Kathmandu") }

        val request = server.requests.single()
        assertEquals("GET", request.method)
        assertEquals(
            "https://geocoding-api.open-meteo.com/v1/search" to mapOf(
                "name" to "Kathmandu",
                "count" to "5",
                "language" to "en",
                "format" to "json"
            ),
            request.url.described()
        )
    }

    @Test
    fun `a forecast decodes, keeping gaps and ignoring fields it does not know`() {
        val expected = OpenMeteoResponse(
            latitude = 27.75,
            longitude = 85.25,
            timezone = ZONE,
            hourly = HourlyData(
                time = listOf("2026-08-24T00:00", "2026-08-24T01:00"),
                pressureMsl = listOf(1013.2f, null),
                surfacePressure = listOf(870.5f, 870.1f)
            )
        )

        assertEquals(expected, fetchForecast())
    }

    @Test
    fun `a search with no matches decodes as no results`() {
        server.body = EMPTY_SEARCH_BODY

        assertNull(runBlocking { geocodingApi.search("Nowhere") }.results)
    }

    @Test
    fun `a server error is thrown, not returned as a forecast`() {
        server.status = HTTP_SERVER_ERROR

        assertThrows(Exception::class.java) { fetchForecast() }
    }

    @Test
    fun `a missing endpoint is thrown, not returned as a forecast`() {
        server.status = HTTP_NOT_FOUND

        assertThrows(Exception::class.java) { fetchForecast() }
    }

    @Test
    fun `a body that is not a forecast is thrown, not returned`() {
        server.body = """{ "error": true, "reason": "Latitude must be in range" }"""

        assertThrows(Exception::class.java) { fetchForecast() }
    }

    @Test
    fun `a connection that fails is thrown`() {
        server.failure = IOException("offline")

        assertThrows(Exception::class.java) { fetchForecast() }
    }
}
