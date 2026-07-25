package com.example.migrainetracker.data.repository

import com.example.migrainetracker.data.local.dao.PressureReadingDao
import com.example.migrainetracker.data.preferences.AppSettings
import com.example.migrainetracker.data.preferences.LocationData
import com.example.migrainetracker.data.preferences.UserPreferences
import com.example.migrainetracker.data.remote.OpenMeteoApi
import com.example.migrainetracker.data.remote.OpenMeteoArchiveApi
import com.example.migrainetracker.data.remote.dto.HourlyData
import com.example.migrainetracker.data.remote.dto.OpenMeteoResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PressureRepositoryTest {

    private val dao = mockk<PressureReadingDao>(relaxed = true)
    private val forecastApi = mockk<OpenMeteoApi>()
    private val archiveApi = mockk<OpenMeteoArchiveApi>()
    private val prefs = mockk<UserPreferences>()

    private lateinit var repository: PressureRepository

    @Before
    fun setup() {
        repository = PressureRepository(dao, forecastApi, archiveApi, prefs)
        
        every { prefs.settings } returns flowOf(
            AppSettings(
                location = LocationData(lat = 52.52, lon = 13.41, name = "Berlin")
            )
        )
    }

    @Test
    fun `refresh fetches forecast and historical data and saves to dao`() = runTest {
        val now = "2023-10-01T12:00"
        val response = OpenMeteoResponse(
            latitude = 52.52,
            longitude = 13.41,
            timezone = "UTC",
            hourly = HourlyData(
                time = listOf(now),
                pressureMsl = listOf(1013.0f),
                surfacePressure = listOf(1000.0f)
            )
        )

        coEvery { forecastApi.getForecast(any(), any(), timezone = any()) } returns response
        coEvery { dao.getLatestHistorical(any()) } returns null // Trigger archive fetch if needed, but let's assume it's fresh enough for this test

        repository.refresh()

        coVerify { forecastApi.getForecast(52.52, 13.41, timezone = "UTC") }
        coVerify { dao.insertHistorical(any()) }
        coVerify { dao.insertForecast(any()) }
    }

    @Test
    fun `refresh skips when no location is set`() = runTest {
        every { prefs.settings } returns flowOf(AppSettings(location = LocationData(lat = 0.0, lon = 0.0)))

        repository.refresh()

        coVerify(exactly = 0) { forecastApi.getForecast(any(), any(), timezone = any()) }
    }
}
