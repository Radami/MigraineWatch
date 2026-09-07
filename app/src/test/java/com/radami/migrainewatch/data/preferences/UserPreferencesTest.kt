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
 * What the settings stream does when the store underneath it cannot be read.
 *
 * DataStore reports a failed read by throwing into the stream rather than returning anything,
 * and an exception in a flow ends every collector of it. Two of those must not end: a screen's,
 * where it reaches `viewModelScope` and takes the process down, and the repository's watch for
 * the user moving — one coroutine, started once, whose death is silent.
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
     * And the stream carries on afterwards, picking the store back up when it can be read.
     *
     * The distinction this test exists for: a `catch` that emits and lets the flow complete
     * passes for a fallback while leaving the location watch just as dead as an exception would
     * — its collector returns, quietly, and never hears anything again. Only a stream that
     * survives the failure can deliver the value after it.
     *
     * Collected with [take] rather than to the end, because a stream that ends is the defect:
     * `toList` here would either hang or, on a flow that completed, quietly assert nothing.
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
