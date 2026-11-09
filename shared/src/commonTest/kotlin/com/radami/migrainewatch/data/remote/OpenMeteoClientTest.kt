package com.radami.migrainewatch.data.remote

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * A call that never answers must fail rather than hang. Refreshes join the one already running,
 * so a single stalled fetch would otherwise hold every later refresh, and an iOS background
 * refresh would overrun its time.
 */
class OpenMeteoClientTest {

    private companion object {
        const val ZURICH_LAT = 47.37
        const val ZURICH_LON = 8.54
        const val AUTO_TIMEZONE = "auto"

        /** Ktor times calls on a real clock, so the test shortens the wait rather than skipping it. */
        val TEST_TIMEOUT = 100.milliseconds
    }

    @Test
    fun aStalledCallFailsAtTheTimeoutInsteadOfHanging() = runTest {
        val neverAnswers = MockEngine { awaitCancellation() }
        val api = KtorOpenMeteoApi(openMeteoHttpClient(neverAnswers, TEST_TIMEOUT))

        assertFailsWith<HttpRequestTimeoutException> {
            api.getForecast(latitude = ZURICH_LAT, longitude = ZURICH_LON, timezone = AUTO_TIMEZONE)
        }
    }
}
