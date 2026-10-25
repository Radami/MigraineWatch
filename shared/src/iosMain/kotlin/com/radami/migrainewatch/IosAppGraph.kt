package com.radami.migrainewatch

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.radami.migrainewatch.data.local.AppDatabase
import com.radami.migrainewatch.data.local.DATABASE_NAME
import com.radami.migrainewatch.data.local.buildAppDatabase
import com.radami.migrainewatch.data.preferences.UserPreferences
import com.radami.migrainewatch.data.remote.GeocodingApi
import com.radami.migrainewatch.data.remote.KtorGeocodingApi
import com.radami.migrainewatch.data.remote.KtorOpenMeteoApi
import com.radami.migrainewatch.data.remote.KtorOpenMeteoArchiveApi
import com.radami.migrainewatch.data.remote.openMeteoHttpClient
import com.radami.migrainewatch.data.repository.PressureRepository
import com.radami.migrainewatch.data.repository.SymptomRepository
import com.radami.migrainewatch.domain.PressureAlertUseCase
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Path.Companion.toPath
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/** The settings file, named as Android's DataStore delegate names it. */
private const val PREFERENCES_FILE_NAME = "user_prefs.preferences_pb"

/**
 * The shared data layer wired up for iOS, standing in for the Hilt modules that do this on
 * Android. Create one for the app's lifetime: it owns the database and the settings file, and
 * DataStore allows only one instance per file in a process.
 *
 * @param directory where the database and settings live; the app's Documents by default.
 */
class IosAppGraph(private val directory: String = documentsDirectory()) {

    /** Outlives every screen, like Android's application scope; see [PressureRepository]. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val httpClient = openMeteoHttpClient(Darwin.create())

    val database: AppDatabase by lazy {
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(name = pathTo(DATABASE_NAME)),
            BundledSQLiteDriver()
        )
    }

    val userPreferences: UserPreferences by lazy {
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
            pathTo(PREFERENCES_FILE_NAME).toPath()
        }
        UserPreferences(dataStore)
    }

    val geocodingApi: GeocodingApi by lazy { KtorGeocodingApi(httpClient) }

    val pressureRepository: PressureRepository by lazy {
        PressureRepository(
            dao = database.pressureReadingDao(),
            forecastApi = KtorOpenMeteoApi(httpClient),
            archiveApi = KtorOpenMeteoArchiveApi(httpClient),
            prefs = userPreferences,
            scope = scope
        )
    }

    val symptomRepository: SymptomRepository by lazy { SymptomRepository(database.symptomEntryDao()) }

    val alertUseCase: PressureAlertUseCase by lazy {
        PressureAlertUseCase(pressureRepository, userPreferences)
    }

    /** Stops background work and releases the database and network client. */
    fun close() {
        scope.cancel()
        httpClient.close()
        database.close()
    }

    private fun pathTo(fileName: String): String = "$directory/$fileName"
}

@OptIn(ExperimentalForeignApi::class)
private fun documentsDirectory(): String {
    val documents = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null
    )
    return requireNotNull(documents?.path) { "The app has no Documents directory" }
}
