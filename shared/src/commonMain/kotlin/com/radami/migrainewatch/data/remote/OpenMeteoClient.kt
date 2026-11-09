package com.radami.migrainewatch.data.remote

import com.radami.migrainewatch.data.remote.dto.GeocodingResponse
import com.radami.migrainewatch.data.remote.dto.OpenMeteoResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
private const val SEARCH_URL = "https://geocoding-api.open-meteo.com/v1/search"

/** Times as instants rather than local clock readings, which repeat an hour when clocks go back. */
private const val UNIX_TIME = "unixtime"

/** Lenient where Open-Meteo is: it adds fields over time and sends nulls for missing hours. */
private val openMeteoJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

/**
 * The longest one call may take, start to finish. Without it each platform falls back to its
 * engine's own idea (about 10 seconds idle on Android, 60 on iOS), and a stalled fetch keeps
 * every later refresh waiting, since refreshes join the one already running. Short enough to
 * fit iOS's background refresh budget of roughly 30 seconds.
 */
val REQUEST_TIMEOUT: Duration = 20.seconds

/** How long to wait for a connection, inside [REQUEST_TIMEOUT]. */
private val CONNECT_TIMEOUT: Duration = 10.seconds

/**
 * The client every Open-Meteo call goes through, on whichever [engine] the platform supplies.
 *
 * [HttpClient.expectSuccess] makes an error status throw, as Retrofit did. Ktor otherwise
 * hands an error response to the decoder, and the repository only reports a failed fetch
 * when the call throws. Timeouts are set here rather than per engine, so both platforms give
 * up on a stalled call at the same point; [requestTimeout] is only ever shortened by tests.
 */
fun openMeteoHttpClient(
    engine: HttpClientEngine,
    requestTimeout: Duration = REQUEST_TIMEOUT
): HttpClient = HttpClient(engine) {
    expectSuccess = true

    install(HttpTimeout) {
        requestTimeoutMillis = requestTimeout.inWholeMilliseconds
        connectTimeoutMillis = minOf(CONNECT_TIMEOUT, requestTimeout).inWholeMilliseconds
    }
}

/**
 * Decodes the body whatever Content-Type it is labelled with, as Retrofit's converter did.
 * Ktor's content negotiation skips a response with no Content-Type, and the mock weather the
 * app serves in development is sent without one.
 */
private suspend inline fun <reified T> HttpResponse.decoded(): T =
    openMeteoJson.decodeFromString(bodyAsText())

class KtorOpenMeteoApi(private val client: HttpClient) : OpenMeteoApi {

    override suspend fun getForecast(
        latitude: Double,
        longitude: Double,
        hourly: String,
        pastDays: Int,
        forecastDays: Int,
        timezone: String
    ): OpenMeteoResponse = client.get(FORECAST_URL) {
        parameter("latitude", latitude)
        parameter("longitude", longitude)
        parameter("hourly", hourly)
        parameter("past_days", pastDays)
        parameter("forecast_days", forecastDays)
        parameter("timezone", timezone)
        parameter("timeformat", UNIX_TIME)
    }.decoded()
}

class KtorOpenMeteoArchiveApi(private val client: HttpClient) : OpenMeteoArchiveApi {

    override suspend fun getArchive(
        latitude: Double,
        longitude: Double,
        hourly: String,
        startDate: String,
        endDate: String,
        timezone: String
    ): OpenMeteoResponse = client.get(ARCHIVE_URL) {
        parameter("latitude", latitude)
        parameter("longitude", longitude)
        parameter("hourly", hourly)
        parameter("start_date", startDate)
        parameter("end_date", endDate)
        parameter("timezone", timezone)
        parameter("timeformat", UNIX_TIME)
    }.decoded()
}

class KtorGeocodingApi(private val client: HttpClient) : GeocodingApi {

    override suspend fun search(
        name: String,
        count: Int,
        language: String,
        format: String
    ): GeocodingResponse = client.get(SEARCH_URL) {
        parameter("name", name)
        parameter("count", count)
        parameter("language", language)
        parameter("format", format)
    }.decoded()
}
