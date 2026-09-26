package com.radami.migrainewatch.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * DataStore signals a failed read by throwing into the flow, which would otherwise end every
 * collector. That must not kill the screen's viewModelScope or the repository's location watch.
 */
class UserPreferencesTest {

    private companion object {
        val LAT = doublePreferencesKey("location_lat")
        val LON = doublePreferencesKey("location_lon")

        const val BERLIN_LAT = 52.52
        const val BERLIN_LON = 13.41
    }

    private val dataStore = mockk<DataStore<Preferences>>()

    private fun preferencesOf(lat: Double, lon: Double): Preferences =
        mutablePreferencesOf().apply {
            this[LAT] = lat
            this[LON] = lon
        }

    @Test
    fun `a stored location is read back`() = runTest {
        every { dataStore.data } returns flowOf(preferencesOf(BERLIN_LAT, BERLIN_LON))

        val location = UserPreferences(dataStore).settings.first().location

        assertEquals(BERLIN_LAT, location.lat, 0.0)
        assertEquals(BERLIN_LON, location.lon, 0.0)
    }

    /** The defaults, not an exception — and certainly not a stream that has stopped. */
    @Test
    fun `a store that cannot be read falls back to the defaults`() = runTest {
        every { dataStore.data } returns flow { throw IOException("store unreadable") }

        val settings = UserPreferences(dataStore).settings.first()

        assertEquals(AppSettings(), settings)
    }

    /**
     * A `catch` that emits and completes looks like a fallback but still kills the collector.
     * Uses [take] instead of collecting to the end, since a stream that ends is the bug.
     */
    @Test
    fun `a failed read does not end the stream`() = runTest {
        var attempts = 0
        every { dataStore.data } returns flow {
            if (attempts++ == 0) throw IOException("store unreadable")
            emit(preferencesOf(BERLIN_LAT, BERLIN_LON))
        }

        val emissions = UserPreferences(dataStore).settings.take(2).toList()

        assertEquals(AppSettings(), emissions.first())
        assertEquals(BERLIN_LAT, emissions.last().location.lat, 0.0)
    }

    /** A failure that is not about reading the file is a bug, and is left to surface. */
    @Test(expected = IllegalStateException::class)
    fun `a failure that is not an IO failure is not swallowed`() = runTest {
        every { dataStore.data } returns flow { throw IllegalStateException("bug") }

        UserPreferences(dataStore).settings.first()
    }
}
