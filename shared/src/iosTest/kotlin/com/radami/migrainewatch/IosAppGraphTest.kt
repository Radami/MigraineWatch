package com.radami.migrainewatch

import com.radami.migrainewatch.data.local.DATABASE_NAME
import com.radami.migrainewatch.data.model.SymptomEntry
import com.radami.migrainewatch.data.preferences.LocationData
import com.radami.migrainewatch.domain.Severity
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The iOS wiring end to end: the bundled SQLite and DataStore actually storing and reading
 * back, in a directory of the test's own rather than the app's Documents.
 */
@OptIn(ExperimentalForeignApi::class)
class IosAppGraphTest {

    private companion object {
        const val PREFERENCES_FILE_NAME = "user_prefs.preferences_pb"
    }

    private val fileManager = NSFileManager.defaultManager
    private val directory = NSTemporaryDirectory() + NSUUID().UUIDString
    private lateinit var graph: IosAppGraph

    private val entry = SymptomEntry(
        date = LocalDate(2026, 8, 24),
        severity = Severity.MIGRAINE,
        triggers = listOf("Stress", "Weather"),
        durationBucket = "4-12h",
        reliefPercent = 50,
        medication = "Ibuprofen",
        notes = "Woke with it",
        createdAt = Instant.fromEpochMilliseconds(1787601600125L),
        updatedAt = Instant.fromEpochMilliseconds(1787607000000L)
    )

    private val kathmandu = LocationData(
        source = "manual",
        lat = 27.7172,
        lon = 85.324,
        name = "Kathmandu, Nepal",
        timezone = "Asia/Kathmandu"
    )

    @BeforeTest
    fun createDirectory() {
        fileManager.createDirectoryAtPath(
            directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null
        )
        graph = IosAppGraph(directory)
    }

    @AfterTest
    fun removeDirectory() {
        graph.close()
        fileManager.removeItemAtPath(directory, error = null)
    }

    @Test
    fun aLoggedDayIsSavedAndReadBack() = runBlocking {
        graph.symptomRepository.save(entry)

        assertEquals(entry, graph.symptomRepository.getByDate(entry.date))
    }

    @Test
    fun settingsAreSavedAndReadBack() = runBlocking {
        graph.userPreferences.saveLocation(kathmandu)

        assertEquals(kathmandu, graph.userPreferences.settings.first().location)
    }

    @Test
    fun dataIsKeptInTheFilesTheAppExpects() = runBlocking {
        graph.symptomRepository.save(entry)
        graph.userPreferences.saveLocation(kathmandu)

        assertTrue(fileManager.fileExistsAtPath("$directory/$DATABASE_NAME"))
        assertTrue(fileManager.fileExistsAtPath("$directory/$PREFERENCES_FILE_NAME"))
    }
}
